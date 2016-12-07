package bdv.server;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.imageio.ImageIO;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.log.Log;
import org.jdom2.Document;
import org.jdom2.JDOMException;
import org.jdom2.input.SAXBuilder;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;

import com.google.gson.GsonBuilder;
import com.google.gson.stream.JsonWriter;

import bdv.BigDataViewer;
import bdv.cache.CacheHints;
import bdv.cache.LoadingStrategy;
import bdv.img.cache.VolatileCell;
import bdv.img.cache.VolatileGlobalCellCache;
import bdv.img.cache.VolatileGlobalCellCache.Key;
import bdv.img.cache.VolatileGlobalCellCache.VolatileCellLoader;
import bdv.img.hdf5.Hdf5ImageLoader;
import bdv.img.hdf5.Hdf5VolatileShortArrayLoader;
import bdv.img.hdf5.Partition;
import bdv.img.remote.AffineTransform3DJsonSerializer;
import bdv.img.remote.RemoteImageLoader;
import bdv.img.remote.RemoteImageLoaderMetaData;
import bdv.model.DataSet;
import bdv.spimdata.SequenceDescriptionMinimal;
import bdv.spimdata.SpimDataMinimal;
import bdv.spimdata.XmlIoSpimDataMinimal;
import bdv.util.ThumbnailGenerator;
import mpicbg.spim.data.SpimDataException;
import net.imglib2.img.basictypeaccess.volatiles.array.VolatileShortArray;
import net.imglib2.realtransform.AffineTransform3D;

public class CellHandler extends ContextHandler
{
	private static final org.eclipse.jetty.util.log.Logger LOG = Log.getLogger( CellHandler.class );

	private VolatileGlobalCellCache cache;

	private final Hdf5VolatileShortArrayLoader loader;

	private final CacheHints cacheHints;

	/**
	 * Full path of the dataset xml file this {@link CellHandler} is serving,
	 * without the ".xml" suffix.
	 */
	private final String baseFilename;

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

	/**
	 * Full path to thumbnail png.
	 */
	private final String thumbnailFilename;

	private boolean active = false;

	private final SequenceDescriptionMinimal seq;

	private Hdf5ImageLoader imgLoader;

	private final String baseUrl;

	/**
	 * DataSet information holder
	 */
	private final DataSet dataSet;

	public CellHandler( final String baseUrl, final DataSet dataSet, final String thumbnailsDirectory ) throws SpimDataException, IOException
	{
		active = true;
		this.baseUrl = baseUrl;
		this.dataSet = dataSet;

		final XmlIoSpimDataMinimal io = new XmlIoSpimDataMinimal();
		final SpimDataMinimal spimData = io.load( dataSet.getXmlPath() );
		seq = spimData.getSequenceDescription();
		imgLoader = ( Hdf5ImageLoader ) seq.getImgLoader();

		cache = imgLoader.getCacheControl();
		loader = imgLoader.getShortArrayLoader();
		cacheHints = new CacheHints( LoadingStrategy.BLOCKING, 0, false );

		// dataSetURL property is used for providing the XML file by replace
		// SequenceDescription>ImageLoader>baseUrl
		final String xmlFilename = dataSet.getXmlPath();
		baseFilename = xmlFilename.endsWith( ".xml" ) ? xmlFilename.substring( 0, xmlFilename.length() - ".xml".length() ) : xmlFilename;
		dataSet.setDatasetUrl( baseUrl );

		datasetXmlString = buildRemoteDatasetXML( io, spimData, baseUrl );
		metadataJson = buildMetadataJsonString( imgLoader, seq );
		settingsXmlString = buildSettingsXML( baseFilename );
		thumbnailFilename = createThumbnail( spimData, baseFilename, dataSet.getName(), thumbnailsDirectory );

		// Calculate dataset size based on the partitions
		long size = new File( xmlFilename.replace( ".xml", ".h5" ) ).length();

		if ( imgLoader.getPartitions().size() > 0 )
			for ( final Partition partition : imgLoader.getPartitions() )
			{
				size += new File( partition.getPath() ).length();
			}

		dataSet.setSize( size );
	}

	@Override
	public void doHandle( final String target, final Request baseRequest, final HttpServletRequest request, final HttpServletResponse response ) throws IOException
	{
		if ( !active )
			return;

		if ( target.equals( "/settings" ) )
		{
			if ( settingsXmlString != null )
				respondWithString( baseRequest, response, "application/xml", settingsXmlString );
			return;
		}

		if ( target.startsWith( "/json" ) )
		{
			provideJson( baseRequest, response );
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
			final Key key = new VolatileGlobalCellCache.Key( timepoint, setup, level, index );
			VolatileCell< ? > cell = cache.getLoadingVolatileCache().getIfPresent( key, cacheHints );
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
				cell = cache.getLoadingVolatileCache().get( key, cacheHints, new VolatileCellLoader<>( loader, timepoint, setup, level, cellDims, cellMin ) );
			}

			@SuppressWarnings( "unchecked" )
			final short[] data = ( ( VolatileCell< VolatileShortArray > ) cell ).getData().getCurrentStorageArray();
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

	private void provideThumbnail( final Request baseRequest, final HttpServletResponse response ) throws IOException
	{
		final Path path = Paths.get( thumbnailFilename );
		if ( Files.exists( path ) )
		{
			final byte[] imageData = Files.readAllBytes(path);
			if ( imageData != null )
			{
				response.setContentType( "image/png" );
				response.setContentLength( imageData.length );
				response.setStatus( HttpServletResponse.SC_OK );
				baseRequest.setHandled( true );

				final OutputStream os = response.getOutputStream();
				os.write( imageData );
				os.close();
			}
		}
	}

	public void setDescription(final String desc)
	{
		dataSet.setDescription( desc );
	}

	public void setCategory(final String cate)
	{
		dataSet.setCategory( cate );
	}

	public void setIndex(final String idx)
	{
		dataSet.setIndex( idx );
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
	 * Create PNG thumbnail file named "{@code <baseFilename>.png}".
	 */
	private static String createThumbnail( final SpimDataMinimal spimData, final String baseFilename, final String datasetName, final String thumbnailsDirectory )
	{
		final String thumbnailFileName = thumbnailsDirectory + "/" + datasetName + ".png";
		final File thumbnailFile = new File( thumbnailFileName );
		if ( !thumbnailFile.isFile() ) // do not recreate thumbnail if it already exists
		{
			final BufferedImage bi = ThumbnailGenerator.makeThumbnail( spimData, baseFilename, Constants.THUMBNAIL_WIDTH, Constants.THUMBNAIL_HEIGHT );
			try
			{
				ImageIO.write( bi, "png", thumbnailFile );
			}
			catch ( final IOException e )
			{
				LOG.warn( "Could not create thumbnail png for dataset \"" + baseFilename + "\"" );
				LOG.warn( e.getMessage() );
			}
		}
		return thumbnailFileName;
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

	/**
	 * Get the current dataset size
	 */
	public long getDataSetSize()
	{
		return dataSet.getSize();
	}

	/**
	 * Get the active status
	 * @return
	 */
	public boolean isActive()
	{
		return active;
	}

	/**
	 * Set the active status
	 * @param active
	 */
	public void setActive( final boolean active ) throws SpimDataException
	{
		this.active = active;

		if ( active )
		{
			imgLoader = ( Hdf5ImageLoader ) seq.getImgLoader();
			cache = imgLoader.getCacheControl();
		}
		else
		{
			imgLoader.close();
			imgLoader = null;
			cache = null;
		}
	}

	/**
	 * Gets data set.
	 *
	 * @return the data set
	 */
	public DataSet getDataSet()
	{
		return dataSet;
	}

	private void provideJson( final Request baseRequest, final HttpServletResponse response ) throws IOException
	{
		response.setContentType( "application/json" );
		response.setStatus( HttpServletResponse.SC_OK );
		baseRequest.setHandled( true );

		final PrintWriter ow = response.getWriter();

		final JsonWriter writer = new JsonWriter( ow );

		writer.setIndent( "\t" );

		writer.beginObject();

		writer.name( getDataSet().getName() ).beginObject();

		writer.name( "id" ).value( getDataSet().getName() );

		writer.name( "category" ).value( getDataSet().getCategory() );

		writer.name( "description" ).value( getDataSet().getDescription() );

		writer.name( "index" ).value( getDataSet().getIndex() );

		writer.name( "thumbnailUrl" ).value( getDataSet().getThumbnailUrl() );

		writer.name( "datasetUrl" ).value( getDataSet().getDatasetUrl() );

		writer.endObject();

		writer.endObject();

		writer.flush();

		writer.close();

		ow.close();
	}

	public void handleXml( final Request baseRequest, final HttpServletResponse response ) throws IOException
	{
		respondWithString( baseRequest, response, "application/xml", datasetXmlString );
	}

	public void handleBdv( final Request baseRequest, final HttpServletResponse response ) throws IOException
	{
		String url = baseUrl;
		if ( url.endsWith( "/" ) )
			url = url.substring( 0, url.lastIndexOf( "/" ) );
		respondWithString( baseRequest, response, "application/bdv", url );
	}
}
