package bdv.util;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.input.SAXBuilder;

import bdv.BigDataViewer;
import bdv.cache.CacheControl;
import bdv.spimdata.SpimDataMinimal;
import bdv.spimdata.XmlIoSpimDataMinimal;
import bdv.tools.InitializeViewerState;
import bdv.tools.brightness.ConverterSetup;
import bdv.tools.brightness.SetupAssignments;
import bdv.tools.transformation.TransformedSource;
import bdv.tools.transformation.XmlIoTransformedSources;
import bdv.viewer.Source;
import bdv.viewer.SourceAndConverter;
import bdv.viewer.render.AccumulateProjectorARGB;
import bdv.viewer.render.MultiResolutionRenderer;
import bdv.viewer.state.SourceGroup;
import bdv.viewer.state.SourceState;
import bdv.viewer.state.ViewerState;
import bdv.viewer.state.XmlIoViewerState;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.ui.PainterThread;
import net.imglib2.ui.RenderTarget;

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

		class ThumbnailTarget implements RenderTarget
		{
			BufferedImage bi;

			@Override
			public BufferedImage setBufferedImage( final BufferedImage bufferedImage )
			{
				bi = bufferedImage;
				return null;
			}

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
		new MultiResolutionRenderer( renderTarget, new PainterThread( null ), new double[] { 1 }, 0, false, 1, null, false, AccumulateProjectorARGB.factory, new CacheControl.Dummy() ).paint( state );
		return renderTarget.bi;
	}

	/**
	 * Currently rendered state (visible sources, transformation, timepoint,
	 * etc.)
	 */
	private final ViewerState state;

	/**
	 * @param sources
	 *            the {@link SourceAndConverter sources} to display.
	 * @param numTimePoints
	 *            number of available timepoints.
	 */
	private ThumbnailGenerator( final List< SourceAndConverter< ? > > sources, final int numTimePoints )
	{
		final int numGroups = 10;
		final ArrayList< SourceGroup > groups = new ArrayList< SourceGroup >( numGroups );
		for ( int i = 0; i < numGroups; ++i )
			groups.add( new SourceGroup( "" ) );

		state = new ViewerState( sources, groups, numTimePoints );
		if ( !sources.isEmpty() )
			state.setCurrentSource( 0 );
	}

	private void stateFromXml( final Element parent )
	{
		final XmlIoViewerState io = new XmlIoViewerState();
		io.restoreFromXml( parent.getChild( io.getTagName() ), state );
	}

	private static class ManualTransformation
	{
		private final ViewerState state ;

		private final XmlIoTransformedSources io;

		public ManualTransformation( final ViewerState state )
		{
			this.state = state;
			io = new XmlIoTransformedSources();
		}

		public void restoreFromXml( final Element parent )
		{
			final Element elem = parent.getChild( io.getTagName() );
			final List< TransformedSource< ? > > sources = getTransformedSources();
			final List< AffineTransform3D > transforms = io.fromXml( elem ).getTransforms();
			if ( sources.size() != transforms.size() )
				System.err.println( "failed to load <" + io.getTagName() + "> source and transform count mismatch" );
			else
				for ( int i = 0; i < sources.size(); ++i )
					sources.get( i ).setFixedTransform( transforms.get( i ) );
		}

		private ArrayList< TransformedSource< ? > > getTransformedSources()
		{
			final ArrayList< TransformedSource< ? > > list = new ArrayList< TransformedSource< ? > >();
			for ( final SourceState< ? > sourceState : state.getSources() )
			{
				final Source< ? > source = sourceState.getSpimSource();
				if ( TransformedSource.class.isInstance( source ) )
					list.add( ( TransformedSource< ? > ) source );
			}
			return list;
		}
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
				new ManualTransformation( state ).restoreFromXml( root );
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
