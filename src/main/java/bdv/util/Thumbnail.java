package bdv.util;

import bdv.BigDataViewer;
import bdv.spimdata.SpimDataMinimal;
import bdv.spimdata.WrapBasicImgLoader;
import bdv.spimdata.XmlIoSpimDataMinimal;
import bdv.tools.InitializeViewerState;
import bdv.tools.brightness.ConverterSetup;
import bdv.tools.brightness.MinMaxGroup;
import bdv.tools.brightness.SetupAssignments;
import bdv.tools.transformation.TransformedSource;
import bdv.tools.transformation.XmlIoTransformedSources;
import bdv.viewer.Source;
import bdv.viewer.SourceAndConverter;
import bdv.viewer.state.SourceState;
import bdv.viewer.state.ViewerState;
import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.generic.sequence.AbstractSequenceDescription;
import mpicbg.spim.data.generic.sequence.BasicViewSetup;
import mpicbg.spim.data.sequence.Angle;
import mpicbg.spim.data.sequence.Channel;
import mpicbg.spim.data.sequence.TimePoint;
import net.imglib2.realtransform.AffineTransform3D;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.JDOMException;
import org.jdom2.input.SAXBuilder;

import javax.imageio.ImageIO;
import javax.swing.*;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by moon on 2/5/15.
 */
public class Thumbnail extends JFrame
{
	protected final ThumbnailGenerator viewer;

	protected final SetupAssignments setupAssignments;

	protected ManualTransformation manualTransformation;

	protected File proposedSettingsFile;

	private static String createSetupName( final BasicViewSetup setup )
	{
		if ( setup.hasName() )
			return setup.getName();

		String name = "";

		final Angle angle = setup.getAttribute( Angle.class );
		if ( angle != null )
			name += ( name.isEmpty() ? "" : " " ) + "a " + angle.getName();

		final Channel channel = setup.getAttribute( Channel.class );
		if ( channel != null )
			name += ( name.isEmpty() ? "" : " " ) + "c " + channel.getName();

		return name;
	}

	public Thumbnail( final String xmlFilename, final int width, final int height ) throws SpimDataException, InterruptedException
	{
		final String thumbnailFile = xmlFilename.replace( ".xml", ".png" );

		final SpimDataMinimal spimData = new XmlIoSpimDataMinimal().load( xmlFilename );
		if ( WrapBasicImgLoader.wrapImgLoaderIfNecessary( spimData ) )
		{
			System.err.println( "WARNING:\nOpening <SpimData> dataset that is not suited for interactive browsing.\nConsider resaving as HDF5 for better performance." );
		}
		final AbstractSequenceDescription< ?, ?, ? > seq = spimData.getSequenceDescription();

		final ArrayList< ConverterSetup > converterSetups = new ArrayList< ConverterSetup >();
		final ArrayList< SourceAndConverter< ? > > sources = new ArrayList< SourceAndConverter< ? > >();
		BigDataViewer.initSetups( spimData, converterSetups, sources );

		final List< TimePoint > timepoints = seq.getTimePoints().getTimePointsOrdered();

		viewer = new ThumbnailGenerator( 800, 600, sources, timepoints.size() );

		manualTransformation = new ManualTransformation( viewer );

		setupAssignments = new SetupAssignments( converterSetups, 0, 65535 );
		if ( setupAssignments.getMinMaxGroups().size() > 0 )
		{
			final MinMaxGroup group = setupAssignments.getMinMaxGroups().get( 0 );
			for ( final ConverterSetup setup : setupAssignments.getConverterSetups() )
				setupAssignments.moveSetupToGroup( setup, group );
		}

		InitializeViewerState.initTransform( viewer.getWidth(), viewer.getHeight(), viewer.getState() );

		if ( !tryLoadSettings( xmlFilename ) )
			InitializeViewerState.initBrightness( 0.001, 0.999, viewer.getState(), setupAssignments );

		final ViewerState renderState = viewer.getState();
		viewer.requestRepaint();
		viewer.paint( renderState );

		final Image img = viewer.getBufferedImage().getScaledInstance( 100, 100, Image.SCALE_SMOOTH );

		final BufferedImage img2 = new BufferedImage( 100, 100, BufferedImage.TYPE_INT_ARGB );
		img2.createGraphics().drawImage( img, 0, 0, null );

		try
		{
			ImageIO.write( img2, "png", new File( thumbnailFile ) );
		}
		catch ( final Exception e )
		{
		}
	}

	protected boolean tryLoadSettings( final String xmlFilename )
	{
		proposedSettingsFile = null;
		if ( xmlFilename.endsWith( ".xml" ) )
		{
			final String settings = xmlFilename.substring( 0, xmlFilename.length() - ".xml".length() ) + ".settings" + ".xml";
			proposedSettingsFile = new File( settings );
			if ( proposedSettingsFile.isFile() )
			{
				try
				{
					loadSettings( settings );
					return true;
				}
				catch ( final Exception e )
				{
					e.printStackTrace();
				}
			}
		}
		return false;
	}

	protected void loadSettings( final String xmlFilename ) throws IOException, JDOMException
	{
		final SAXBuilder sax = new SAXBuilder();
		final Document doc = sax.build( xmlFilename );
		final Element root = doc.getRootElement();
		viewer.stateFromXml( root );
		setupAssignments.restoreFromXml( root );
		manualTransformation.restoreFromXml( root );
		viewer.requestRepaint();
	}

	public static void main( final String[] args )
	{
		final String fn = args[ 0 ];

		try
		{
			final Thumbnail thumb = new Thumbnail( fn, 800, 600 );
		}
		catch ( final Exception e )
		{
			e.printStackTrace();
		}
	}

	public class ManualTransformation
	{
		protected final ThumbnailGenerator viewer;

		protected final XmlIoTransformedSources io;

		public ManualTransformation( final ThumbnailGenerator viewer )
		{
			this.viewer = viewer;
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
			final ViewerState state = viewer.getState();
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
}
