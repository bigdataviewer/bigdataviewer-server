/*-
 * #%L
 * A web server for BigDataViewer datasets.
 * %%
 * Copyright (C) 2014 - 2022 BigDataViewer developers.
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
package bdv.util;

import bdv.BigDataViewer;
import bdv.cache.CacheControl;
import bdv.spimdata.SpimDataMinimal;
import bdv.spimdata.XmlIoSpimDataMinimal;
import bdv.tools.InitializeViewerState;
import bdv.tools.brightness.ConverterSetup;
import bdv.tools.brightness.SetupAssignments;
import bdv.tools.transformation.ManualTransformation;
import bdv.viewer.BasicViewerState;
import bdv.viewer.SourceAndConverter;
import bdv.viewer.SynchronizedViewerState;
import bdv.viewer.ViewerState;
import bdv.viewer.render.AccumulateProjectorARGB;
import bdv.viewer.render.MultiResolutionRenderer;
import bdv.viewer.render.RenderTarget;
import bdv.viewer.render.awt.BufferedImageRenderResult;
import bdv.viewer.state.XmlIoViewerState;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import net.imglib2.realtransform.AffineTransform3D;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.input.SAXBuilder;

import static bdv.viewer.DisplayMode.SINGLE;
import static bdv.viewer.Interpolation.NEARESTNEIGHBOR;

/**
 * Created by moon on 2/5/15.
 */
public class ThumbnailGenerator
{
	/**
	 * Create a thumbnail image for a dataset. If there is a settings.xml file
	 * for the dataset, these settings are used for creating the thumbnail.
	 *
	 * @param spimData
	 *            the dataset.
	 * @param baseFilename
	 *            full path of dataset xml file, without the ".xml" extension.
	 *            this is used to derive the name of the settings.xml file.
	 * @param width
	 *            width of the thumbnail image.
	 * @param height
	 *            height of the thumbnail image.
	 * @return thumbnail image
	 */
	public static BufferedImage makeThumbnail( final SpimDataMinimal spimData, final String baseFilename, final int width, final int height )
	{
		final ArrayList< ConverterSetup > converterSetups = new ArrayList< ConverterSetup >();
		final ArrayList< SourceAndConverter< ? > > sources = new ArrayList< SourceAndConverter< ? > >();
		BigDataViewer.initSetups( spimData, converterSetups, sources );

		final int numTimepoints = spimData.getSequenceDescription().getTimePoints().size();
		final ThumbnailGenerator generator = new ThumbnailGenerator( sources, numTimepoints );
		final ViewerState state = generator.state;

		final SetupAssignments setupAssignments = new SetupAssignments( converterSetups, 0, 65535 );
		final AffineTransform3D initTransform = InitializeViewerState.initTransform( width, height, false, state );
		state.setViewerTransform( initTransform );

		if ( !generator.tryLoadSettings( baseFilename, setupAssignments ) )
			InitializeViewerState.initBrightness( 0.001, 0.999, state, setupAssignments );

		class ThumbnailTarget implements RenderTarget< BufferedImageRenderResult >
		{
			final BufferedImageRenderResult renderResult = new BufferedImageRenderResult();

			@Override
			public BufferedImageRenderResult getReusableRenderResult()
			{
				return renderResult;
			}

			@Override
			public BufferedImageRenderResult createRenderResult()
			{
				return new BufferedImageRenderResult();
			}

			@Override
			public void setRenderResult( final BufferedImageRenderResult renderResult )
			{}

			@Override
			public int getWidth()
			{
				return width;
			}

			@Override
			public int getHeight()
			{
				return height;
			}
		}
		final ThumbnailTarget renderTarget = new ThumbnailTarget();
		final MultiResolutionRenderer renderer = new MultiResolutionRenderer(
				renderTarget, () -> {}, new double[] { 1 }, 0, 1, null, false,
				AccumulateProjectorARGB.factory, new CacheControl.Dummy() );
		renderer.paint( state );
		return renderTarget.renderResult.getBufferedImage();
	}

	/**
	 * Initialize ViewerState with the given {@code sources} and {@code numTimepoints}.
	 * Set up {@code numGroups} SourceGroups named "group 1", "group 2", etc. Add the
	 * first source to the first group, the second source to the second group etc.
	 *
	 * TODO: Setting up groups like this doesn't make a lot of sense. This just
	 *   replicates legacy behaviour. The remaining thing that stands in the way of
	 *   removing it is ViewerState serialization, which assumes that there are always 10
	 *   groups ... m(
	 */
	private static SynchronizedViewerState setupState( final List< SourceAndConverter< ? > > sources, final int numTimepoints, final int numGroups )
	{
		final SynchronizedViewerState state = new SynchronizedViewerState( new BasicViewerState() );
		state.addSources( sources );
		state.setSourcesActive( sources, true );
		for ( int i = 0; i < numGroups; ++i ) {
			final bdv.viewer.SourceGroup handle = new bdv.viewer.SourceGroup();
			state.addGroup( handle );
			state.setGroupName( handle,  "group " + ( i + 1 ) );
			state.setGroupActive( handle, true );
			if ( i < sources.size() )
				state.addSourceToGroup( sources.get( i ), handle );
		}
		state.setNumTimepoints( numTimepoints );
		state.setInterpolation( NEARESTNEIGHBOR );
		state.setDisplayMode( SINGLE );
		state.setCurrentSource( sources.isEmpty() ? null : sources.get( 0 ) );
		state.setCurrentGroup( numGroups <= 0 ? null : state.getGroups().get( 0 ) );

		return state;
	}

	/**
	 * Currently rendered state (visible sources, transformation, timepoint,
	 * etc.)
	 */
	private final SynchronizedViewerState state;

	/**
	 * @param sources
	 *            the {@link SourceAndConverter sources} to display.
	 * @param numTimePoints
	 *            number of available timepoints.
	 */
	private ThumbnailGenerator( final List< SourceAndConverter< ? > > sources, final int numTimePoints )
	{
		final int numGroups = 10;
		state = setupState( sources, numTimePoints, numGroups );
	}

	private void stateFromXml( final Element parent )
	{
		final XmlIoViewerState io = new XmlIoViewerState();
		final bdv.viewer.state.ViewerState deprecatedState = new bdv.viewer.state.ViewerState( state );
		io.restoreFromXml( parent.getChild( io.getTagName() ), deprecatedState );
	}

	private boolean tryLoadSettings( final String baseFilename, final SetupAssignments setupAssignments )
	{
		final String settings = baseFilename + ".settings.xml";
		if ( new File( settings ).isFile() )
		{
			try
			{
				final SAXBuilder sax = new SAXBuilder();
				final Document doc = sax.build( settings );
				final Element root = doc.getRootElement();
				stateFromXml( root );
				setupAssignments.restoreFromXml( root );
				new ManualTransformation( state.getSources() ).restoreFromXml( root );
				return true;
			}
			catch ( final Exception e )
			{
				e.printStackTrace();
			}
		}
		return false;
	}

	public static void main( final String[] args )
	{
		final String fn = args[ 0 ];
		try
		{
			final SpimDataMinimal spimData = new XmlIoSpimDataMinimal().load( fn );
			final String thumbnailFile = fn.replace( ".xml", ".png" );
			final BufferedImage bi = makeThumbnail( spimData, fn, 100, 100 );

			try
			{
				ImageIO.write( bi, "png", new File( thumbnailFile ) );
			}
			catch ( final Exception e )
			{
			}

		}
		catch ( final Exception e )
		{
			e.printStackTrace();
		}
	}
}
