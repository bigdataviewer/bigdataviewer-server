package bdv.util;

import bdv.SpimSource;
import bdv.VolatileSpimSource;
import bdv.spimdata.SpimDataMinimal;
import bdv.spimdata.WrapBasicImgLoader;
import bdv.spimdata.XmlIoSpimDataMinimal;
import bdv.tools.brightness.ConverterSetup;
import bdv.tools.brightness.MinMaxGroup;
import bdv.tools.brightness.RealARGBColorConverterSetup;
import bdv.tools.brightness.SetupAssignments;
import bdv.tools.transformation.TransformedSource;
import bdv.tools.transformation.XmlIoTransformedSources;
import bdv.viewer.Source;
import bdv.viewer.SourceAndConverter;
import bdv.viewer.state.SourceState;
import bdv.viewer.state.ViewerState;
import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.generic.AbstractSpimData;
import mpicbg.spim.data.generic.sequence.AbstractSequenceDescription;
import mpicbg.spim.data.generic.sequence.BasicViewSetup;
import mpicbg.spim.data.sequence.Angle;
import mpicbg.spim.data.sequence.Channel;
import mpicbg.spim.data.sequence.TimePoint;
import net.imglib2.Volatile;
import net.imglib2.converter.Converter;
import net.imglib2.converter.TypeIdentity;
import net.imglib2.display.RealARGBColorConverter;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.volatiles.VolatileARGBType;
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

	private static < T extends RealType< T >, V extends Volatile< T > & RealType< V > > void initSetupsRealType(
			final AbstractSpimData< ? > spimData,
			final T type,
			final List< ConverterSetup > converterSetups,
			final List< SourceAndConverter< ? > > sources )
	{
		if ( spimData.getSequenceDescription().getImgLoader() instanceof WrapBasicImgLoader )
		{
			initSetupsRealTypeNonVolatile( spimData, type, converterSetups, sources );
			return;
		}
		final double typeMin = type.getMinValue();
		final double typeMax = type.getMaxValue();
		final AbstractSequenceDescription< ?, ?, ? > seq = spimData.getSequenceDescription();
		for ( final BasicViewSetup setup : seq.getViewSetupsOrdered() )
		{
			final RealARGBColorConverter< V > vconverter = new RealARGBColorConverter.Imp0< V >( typeMin, typeMax );
			vconverter.setColor( new ARGBType( 0xffffffff ) );
			final RealARGBColorConverter< T > converter = new RealARGBColorConverter.Imp1< T >( typeMin, typeMax );
			converter.setColor( new ARGBType( 0xffffffff ) );

			final int setupId = setup.getId();
			final String setupName = createSetupName( setup );
			final VolatileSpimSource< T, V > vs = new VolatileSpimSource< T, V >( spimData, setupId, setupName );
			final SpimSource< T > s = vs.nonVolatile();

			// Decorate each source with an extra transformation, that can be
			// edited manually in this viewer.
			final TransformedSource< V > tvs = new TransformedSource< V >( vs );
			final TransformedSource< T > ts = new TransformedSource< T >( s, tvs );

			final SourceAndConverter< V > vsoc = new SourceAndConverter< V >( tvs, vconverter );
			final SourceAndConverter< T > soc = new SourceAndConverter< T >( ts, converter, vsoc );

			sources.add( soc );
			converterSetups.add( new RealARGBColorConverterSetup( setupId, converter, vconverter ) );
		}
	}

	private static < T extends RealType< T > > void initSetupsRealTypeNonVolatile(
			final AbstractSpimData< ? > spimData,
			final T type,
			final List< ConverterSetup > converterSetups,
			final List< SourceAndConverter< ? > > sources )
	{
		final double typeMin = type.getMinValue();
		final double typeMax = type.getMaxValue();
		final AbstractSequenceDescription< ?, ?, ? > seq = spimData.getSequenceDescription();
		for ( final BasicViewSetup setup : seq.getViewSetupsOrdered() )
		{
			final RealARGBColorConverter< T > converter = new RealARGBColorConverter.Imp1< T >( typeMin, typeMax );
			converter.setColor( new ARGBType( 0xffffffff ) );

			final int setupId = setup.getId();
			final String setupName = createSetupName( setup );
			final SpimSource< T > s = new SpimSource< T >( spimData, setupId, setupName );

			// Decorate each source with an extra transformation, that can be
			// edited manually in this viewer.
			final TransformedSource< T > ts = new TransformedSource< T >( s );
			final SourceAndConverter< T > soc = new SourceAndConverter< T >( ts, converter );

			sources.add( soc );
			converterSetups.add( new RealARGBColorConverterSetup( setupId, converter ) );
		}
	}

	private static void initSetupsARGBType(
			final AbstractSpimData< ? > spimData,
			final ARGBType type,
			final List< ConverterSetup > converterSetups,
			final List< SourceAndConverter< ? > > sources )
	{
		final AbstractSequenceDescription< ?, ?, ? > seq = spimData.getSequenceDescription();
		for ( final BasicViewSetup setup : seq.getViewSetupsOrdered() )
		{
			final Converter< VolatileARGBType, ARGBType > vconverter = new Converter< VolatileARGBType, ARGBType >()
			{
				@Override
				public void convert( final VolatileARGBType input, final ARGBType output )
				{
					output.set( input.get() );
				}
			};
			final TypeIdentity< ARGBType > converter = new TypeIdentity< ARGBType >();

			final int setupId = setup.getId();
			final String setupName = createSetupName( setup );
			final VolatileSpimSource< ARGBType, VolatileARGBType > vs = new VolatileSpimSource< ARGBType, VolatileARGBType >( spimData, setupId, setupName );
			final SpimSource< ARGBType > s = vs.nonVolatile();

			// Decorate each source with an extra transformation, that can be
			// edited manually in this viewer.
			final TransformedSource< VolatileARGBType > tvs = new TransformedSource< VolatileARGBType >( vs );
			final TransformedSource< ARGBType > ts = new TransformedSource< ARGBType >( s, tvs );

			final SourceAndConverter< VolatileARGBType > vsoc = new SourceAndConverter< VolatileARGBType >( tvs, vconverter );
			final SourceAndConverter< ARGBType > soc = new SourceAndConverter< ARGBType >( ts, converter, vsoc );

			sources.add( soc );
		}
	}

	@SuppressWarnings( { "unchecked", "rawtypes" } )
	public static void initSetups(
			final AbstractSpimData< ? > spimData,
			final List< ConverterSetup > converterSetups,
			final List< SourceAndConverter< ? > > sources )
	{
		final Object type = spimData.getSequenceDescription().getImgLoader().getImageType();
		if ( RealType.class.isInstance( type ) )
			initSetupsRealType( spimData, ( RealType ) type, converterSetups, sources );
		else if ( ARGBType.class.isInstance( type ) )
			initSetupsARGBType( spimData, ( ARGBType ) type, converterSetups, sources );
		else
			throw new IllegalArgumentException( "ImgLoader of type " + type.getClass() + " not supported." );
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
		initSetups( spimData, converterSetups, sources );

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

		InitializeThumbState.initTransform( viewer );

		if ( !tryLoadSettings( xmlFilename ) )
			InitializeThumbState.initBrightness( 0.001, 0.999, viewer, setupAssignments );

		ViewerState renderState = viewer.getState();
		viewer.requestRepaint();
		viewer.paint( renderState );

		Image img = viewer.getBufferedImage().getScaledInstance( 100, 100, Image.SCALE_SMOOTH );

		BufferedImage img2 = new BufferedImage( 100, 100, BufferedImage.TYPE_INT_ARGB );
		img2.createGraphics().drawImage( img, 0, 0, null );

		try
		{
			ImageIO.write( img2, "png", new File( thumbnailFile ) );
		}
		catch ( Exception e )
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
			Thumbnail thumb = new Thumbnail( fn, 800, 600 );
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
