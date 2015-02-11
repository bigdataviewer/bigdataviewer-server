package bdv.server;

import bdv.BigDataViewer;
import bdv.img.cache.CacheHints;
import bdv.img.cache.LoadingStrategy;
import bdv.img.cache.VolatileCell;
import bdv.img.cache.VolatileGlobalCellCache;
import bdv.img.hdf5.Hdf5ImageLoader;
import bdv.img.remote.AffineTransform3DJsonSerializer;
import bdv.img.remote.RemoteImageLoader;
import bdv.img.remote.RemoteImageLoaderMetaData;
import bdv.spimdata.SequenceDescriptionMinimal;
import bdv.spimdata.SpimDataMinimal;
import bdv.spimdata.XmlIoSpimDataMinimal;
import bdv.util.Thumbnail;

import com.google.gson.GsonBuilder;

import mpicbg.spim.data.SpimDataException;
import net.imglib2.img.basictypeaccess.volatiles.array.VolatileShortArray;
import net.imglib2.realtransform.AffineTransform3D;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.log.Log;
import org.jdom2.Document;
import org.jdom2.JDOMException;
import org.jdom2.input.SAXBuilder;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;

import javax.imageio.ImageIO;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.awt.image.BufferedImage;
import java.io.*;

public class CellHandler extends ContextHandler
{
	private static final org.eclipse.jetty.util.log.Logger LOG = Log.getLogger( CellHandler.class );

	private final VolatileGlobalCellCache< VolatileShortArray > cache;

	private final CacheHints cacheHints;

	/**
	 * Full path of the dataset xml file this {@link CellHandler} is serving.
	 */
	private final String xmlFilename;

	/**
	 * Full path of the dataset xml file this {@link CellHandler} is serving,
	 * without the ".xml" suffix.
	 */
	private final String baseFilename;

	private final String dataSetURL;

	/**
	 * Cached dataset XML to be send to and opened by {@link BigDataViewer}
	 * clients.
	 */
	private final String datasetXmlString;

	/**
	 * Cached JSON representation of the {@link RemoteImageLoaderMetaData} to be
	 * send to clients.
	 */
	private final String metadataJson;

	/**
	 * Cached dataset.settings XML to be send to clients. May be null if no
	 * settings file exists for the dataset.
	 */
	private final String settingsXmlString;

	public CellHandler( final String baseUrl, final String xmlFilename ) throws SpimDataException, IOException
	{
		final XmlIoSpimDataMinimal io = new XmlIoSpimDataMinimal();
		final SpimDataMinimal spimData = io.load( xmlFilename );
		final SequenceDescriptionMinimal seq = spimData.getSequenceDescription();
		final Hdf5ImageLoader imgLoader = ( Hdf5ImageLoader ) seq.getImgLoader();

		cache = imgLoader.getCache();
		cacheHints = new CacheHints( LoadingStrategy.BLOCKING, 0, false );

		// dataSetURL property is used for providing the XML file by replace
		// SequenceDescription>ImageLoader>baseUrl
		this.xmlFilename = xmlFilename;
		baseFilename = xmlFilename.endsWith( ".xml" ) ? xmlFilename.substring( 0, xmlFilename.length() - ".xml".length() ) : xmlFilename;
		dataSetURL = baseUrl;

		datasetXmlString = buildRemoteDatasetXML( io, spimData, baseUrl );
		metadataJson = buildMetadataJsonString( imgLoader, seq );
		settingsXmlString = buildSettingsXML( baseFilename );
	}

	@Override
	public void doHandle( final String target, final Request baseRequest, final HttpServletRequest request, final HttpServletResponse response ) throws IOException
	{
		if ( target.equals( "/settings" ) )
		{
			if ( settingsXmlString != null )
				respondWithString( baseRequest, response, "application/xml", settingsXmlString );
			return;
		}

		if ( target.equals( "/png" ) )
		{
			provideThumbnail( baseRequest, response );
			return;
		}

		final String cellString = request.getParameter( "p" );

		if ( cellString == null )
		{
			respondWithString( baseRequest, response, "application/xml", datasetXmlString );
			return;
		}

		final String[] parts = cellString.split( "/" );
		if ( parts[ 0 ].equals( "cell" ) )
		{
			final int index = Integer.parseInt( parts[ 1 ] );
			final int timepoint = Integer.parseInt( parts[ 2 ] );
			final int setup = Integer.parseInt( parts[ 3 ] );
			final int level = Integer.parseInt( parts[ 4 ] );
			VolatileCell< VolatileShortArray > cell = cache.getGlobalIfCached( timepoint, setup, level, index, cacheHints );
			if ( cell == null )
			{
				final int[] cellDims = new int[] {
						Integer.parseInt( parts[ 5 ] ),
						Integer.parseInt( parts[ 6 ] ),
						Integer.parseInt( parts[ 7 ] ) };
				final long[] cellMin = new long[] {
						Long.parseLong( parts[ 8 ] ),
						Long.parseLong( parts[ 9 ] ),
						Long.parseLong( parts[ 10 ] ) };
				cell = cache.createGlobal( cellDims, cellMin, timepoint, setup, level, index, cacheHints );
			}

			final short[] data = cell.getData().getCurrentStorageArray();
			final byte[] buf = new byte[ 2 * data.length ];
			for ( int i = 0, j = 0; i < data.length; i++ )
			{
				final short s = data[ i ];
				buf[ j++ ] = ( byte ) ( ( s >> 8 ) & 0xff );
				buf[ j++ ] = ( byte ) ( s & 0xff );
			}

			response.setContentType( "application/octet-stream" );
			response.setContentLength( buf.length );
			response.setStatus( HttpServletResponse.SC_OK );
			baseRequest.setHandled( true );
			final OutputStream os = response.getOutputStream();
			os.write( buf );
			os.close();
		}
		else if ( parts[ 0 ].equals( "init" ) )
		{
			respondWithString( baseRequest, response, "application/json", metadataJson );
		}
	}

	public void provideThumbnail( final Request baseRequest, final HttpServletResponse response )
	{
		// Just check it once
		final File pngFile = new File( baseFilename + ".png" );

		if ( pngFile.exists() )
		{
			byte[] imageData = null;
			try
			{
				final BufferedImage image = ImageIO.read( pngFile );
				final ByteArrayOutputStream baos = new ByteArrayOutputStream();
				ImageIO.write( image, "png", baos );
				imageData = baos.toByteArray();
			}
			catch ( final IOException e )
			{
				e.printStackTrace();
			}

			if ( imageData != null )
			{
				response.setContentType( "image/png" );
				response.setContentLength( imageData.length );
				response.setStatus( HttpServletResponse.SC_OK );
				baseRequest.setHandled( true );

				try
				{
					final OutputStream os = response.getOutputStream();
					os.write( imageData );
					os.close();
				}
				catch ( final IOException e )
				{
					return;
				}
			}
		}
		else
		{
			return;
		}
	}

	public String getXmlFile()
	{
		return xmlFilename;
	}

	public String getDataSetURL()
	{
		return dataSetURL;
	}

	public String getThumbnailUrl()
	{
		final File pngFile = new File( baseFilename + ".png" );
		if ( pngFile.exists() )
			return dataSetURL + "png";

		// No thumbnail
		// Trigger to generate thumbnail here
		try
		{
			final Thumbnail thumb = new Thumbnail( xmlFilename, 800, 600 );
		}
		catch ( final Exception e )
		{
			e.printStackTrace();
		}

		return dataSetURL + "png";
	}

	public String getDescription()
	{
		throw new UnsupportedOperationException();
	}

	/**
	 * Create a JSON representation of the {@link RemoteImageLoaderMetaData}
	 * (image sizes and resolutions) provided by the given
	 * {@link Hdf5ImageLoader}.
	 */
	private static String buildMetadataJsonString( final Hdf5ImageLoader imgLoader, final SequenceDescriptionMinimal seq )
	{
		final RemoteImageLoaderMetaData metadata = new RemoteImageLoaderMetaData( imgLoader, seq );
		final GsonBuilder gsonBuilder = new GsonBuilder();
		gsonBuilder.registerTypeAdapter( AffineTransform3D.class, new AffineTransform3DJsonSerializer() );
		gsonBuilder.enableComplexMapKeySerialization();
		return gsonBuilder.create().toJson( metadata );
	}

	/**
	 * Create a modified dataset XML by replacing the ImageLoader with an
	 * {@link RemoteImageLoader} pointing to the data we are serving.
	 */
	private static String buildRemoteDatasetXML( final XmlIoSpimDataMinimal io, final SpimDataMinimal spimData, final String baseUrl ) throws IOException, SpimDataException
	{
		final SpimDataMinimal s = new SpimDataMinimal( spimData, new RemoteImageLoader( baseUrl, false ) );
		final Document doc = new Document( io.toXml( s, s.getBasePath() ) );
		final XMLOutputter xout = new XMLOutputter( Format.getPrettyFormat() );
		final StringWriter sw = new StringWriter();
		xout.output( doc, sw );
		return sw.toString();
	}

	/**
	 * Read {@code baseFilename.settings.xml} into a string if it exists.
	 *
	 * @return contents of {@code baseFilename.settings.xml} or {@code null} if
	 *         that file couldn't be read.
	 */
	private static String buildSettingsXML( final String baseFilename )
	{
		final String settings = baseFilename + ".settings.xml";
		if ( new File( settings ).exists() )
		{
			try
			{
				final SAXBuilder sax = new SAXBuilder();
				final Document doc = sax.build( settings );
				final XMLOutputter xout = new XMLOutputter( Format.getPrettyFormat() );
				final StringWriter sw = new StringWriter();
				xout.output( doc, sw );
				return sw.toString();
			}
			catch ( JDOMException | IOException e )
			{
				LOG.warn( "Could not read settings file \"" + settings + "\"" );
				LOG.warn( e.getMessage() );
			}
		}
		return null;
	}

	/**
	 * Handle request by sending a UTF-8 string.
	 */
	private static void respondWithString( final Request baseRequest, final HttpServletResponse response, final String contentType, final String string ) throws IOException
	{
		response.setContentType( contentType );
		response.setCharacterEncoding( "UTF-8" );
		response.setStatus( HttpServletResponse.SC_OK );
		baseRequest.setHandled( true );

		final PrintWriter ow = response.getWriter();
		ow.write( string );
		ow.close();
	}
}
