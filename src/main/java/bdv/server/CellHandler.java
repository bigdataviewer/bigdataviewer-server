/*-
 * #%L
 * A web server for BigDataViewer datasets.
 * %%
 * Copyright (C) 2014 - 2023 BigDataViewer developers.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */
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

import java.util.concurrent.ExecutionException;
import javax.imageio.ImageIO;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import net.imglib2.cache.CacheLoader;
import net.imglib2.cache.LoaderCache;
import net.imglib2.cache.ref.SoftRefLoaderCache;
import net.imglib2.img.cell.Cell;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.log.Log;
import org.jdom2.Document;
import org.jdom2.JDOMException;
import org.jdom2.input.SAXBuilder;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;

import com.google.gson.GsonBuilder;

import bdv.BigDataViewer;
import bdv.img.cache.VolatileGlobalCellCache;
import bdv.img.hdf5.Hdf5ImageLoader;
import bdv.img.hdf5.Hdf5ImageLoader.SetupImgLoader;
import bdv.img.remote.AffineTransform3DJsonSerializer;
import bdv.img.remote.RemoteImageLoader;
import bdv.img.remote.RemoteImageLoaderMetaData;
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

	/**
	 * Key for a cell identified by timepoint, setup, level, and index
	 * (flattened spatial coordinate).
	 */
	public static class Key
	{
		private final int timepoint;

		private final int setup;

		private final int level;

		private final long index;

		private final String[] parts;

		/**
		 * Create a Key for the specified cell. Note that {@code cellDims} and
		 * {@code cellMin} are not used for {@code hashcode()/equals()}.
		 *
		 * @param timepoint
		 *            timepoint coordinate of the cell
		 * @param setup
		 *            setup coordinate of the cell
		 * @param level
		 *            level coordinate of the cell
		 * @param index
		 *            index of the cell (flattened spatial coordinate of the
		 *            cell)
		 */
		public Key( final int timepoint, final int setup, final int level, final long index, final String[] parts )
		{
			this.timepoint = timepoint;
			this.setup = setup;
			this.level = level;
			this.index = index;
			this.parts = parts;

			int value = Long.hashCode( index );
			value = 31 * value + level;
			value = 31 * value + setup;
			value = 31 * value + timepoint;
			hashcode = value;
		}

		@Override
		public boolean equals( final Object other )
		{
			if ( this == other )
				return true;
			if ( !( other instanceof VolatileGlobalCellCache.Key ) )
				return false;
			final Key that = ( Key ) other;
			return ( this.index == that.index ) && ( this.timepoint == that.timepoint ) && ( this.setup == that.setup ) && ( this.level == that.level );
		}

		final int hashcode;

		@Override
		public int hashCode()
		{
			return hashcode;
		}
	}

	private final CacheLoader< Key, Cell< ? > > loader;

	private final LoaderCache< Key, Cell< ? > > cache;

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

	/**
	 * Full path to thumbnail png.
	 */
	private final String thumbnailFilename;

	public CellHandler( final String baseUrl, final String xmlFilename, final String datasetName, final String thumbnailsDirectory ) throws SpimDataException, IOException
	{
		final XmlIoSpimDataMinimal io = new XmlIoSpimDataMinimal();
		final SpimDataMinimal spimData = io.load( xmlFilename );
		final SequenceDescriptionMinimal seq = spimData.getSequenceDescription();
		final Hdf5ImageLoader imgLoader = ( Hdf5ImageLoader ) seq.getImgLoader();

		loader = key -> {
			final int[] cellDims = new int[] {
					Integer.parseInt( key.parts[ 5 ] ),
					Integer.parseInt( key.parts[ 6 ] ),
					Integer.parseInt( key.parts[ 7 ] ) };
			final long[] cellMin = new long[] {
					Long.parseLong( key.parts[ 8 ] ),
					Long.parseLong( key.parts[ 9 ] ),
					Long.parseLong( key.parts[ 10 ] ) };
			SetupImgLoader<?, ?> setupImgLoader = imgLoader.getSetupImgLoader(key.setup);
			return new Cell<>( cellDims, cellMin, setupImgLoader.getVolatileImage(key.timepoint, key.level));
		};

		cache = new SoftRefLoaderCache<>();

		// dataSetURL property is used for providing the XML file by replace
		// SequenceDescription>ImageLoader>baseUrl
		this.xmlFilename = xmlFilename;
		baseFilename = xmlFilename.endsWith( ".xml" ) ? xmlFilename.substring( 0, xmlFilename.length() - ".xml".length() ) : xmlFilename;
		dataSetURL = baseUrl;

		datasetXmlString = buildRemoteDatasetXML( io, spimData, baseUrl );
		metadataJson = buildMetadataJsonString( imgLoader, seq );
		settingsXmlString = buildSettingsXML( baseFilename );
		thumbnailFilename = createThumbnail( spimData, baseFilename, datasetName, thumbnailsDirectory );
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
			final Key key = new Key( timepoint, setup, level, index, parts );
			short[] data;
			try
			{
				final Cell< ? > cell = cache.get( key, loader );
				data = ( ( VolatileShortArray ) cell.getData() ).getCurrentStorageArray();
			}
			catch ( ExecutionException e )
			{
				data = new short[ 0 ];
			}

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
}
