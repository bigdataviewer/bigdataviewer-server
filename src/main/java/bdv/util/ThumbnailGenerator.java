package bdv.util;

import bdv.img.cache.Cache;
import bdv.viewer.Interpolation;
import bdv.viewer.SourceAndConverter;
import bdv.viewer.VisibilityAndGrouping;
import bdv.viewer.render.MultiResolutionRenderer;
import bdv.viewer.state.SourceGroup;
import bdv.viewer.state.ViewerState;
import bdv.viewer.state.XmlIoViewerState;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.ui.PainterThread;
import net.imglib2.ui.RenderTarget;
import net.imglib2.ui.TransformListener;
import org.jdom2.Element;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static bdv.viewer.VisibilityAndGrouping.Event.*;

/**
 * Created by moon on 2/5/15.
 */
public class ThumbnailGenerator implements TransformListener< AffineTransform3D >, VisibilityAndGrouping.UpdateListener
{
	protected final int width;

	protected final int height;

	/**
	 * Currently rendered state (visible sources, transformation, timepoint,
	 * etc.) A copy can be obtained by {@link #getState()}.
	 */
	protected final ViewerState state;

	/**
	 * Renders the current state for the thumbnail.
	 */
	protected final MultiResolutionRenderer imageRenderer;

	protected final ThumbnailTarget renderTarget;

	/**
	 * Transformation set by the interactive viewer.
	 */
	protected final AffineTransform3D viewerTransform;

	/**
	 * Manages visibility and currentness of sources and groups, as well as
	 * grouping of sources, and display mode.
	 */
	protected final VisibilityAndGrouping visibilityAndGrouping;

	/**
	 * These listeners will be notified about changes to the
	 * {@link #viewerTransform}. This is done <em>before</em> calling
	 * {@link #requestRepaint()} so listeners have the chance to interfere.
	 */
	protected final CopyOnWriteArrayList< TransformListener< AffineTransform3D > > transformListeners;

	/**
	 * @param sources
	 *            the {@link SourceAndConverter sources} to display.
	 * @param numTimePoints
	 *            number of available timepoints.
	 */
	public ThumbnailGenerator( final int width, final int height, final List< SourceAndConverter< ? > > sources, final int numTimePoints )
	{
		this.width = width;
		this.height = height;

		final int numGroups = 10;
		final ArrayList< SourceGroup > groups = new ArrayList< SourceGroup >( numGroups );
		for ( int i = 0; i < numGroups; ++i )
		{
			final SourceGroup g = new SourceGroup( "group " + Integer.toString( i + 1 ) );
			if ( i < sources.size() )
			{
				g.addSource( i );
			}
			groups.add( g );
		}

		state = new ViewerState( sources, groups, numTimePoints );
		if ( !sources.isEmpty() )
			state.setCurrentSource( 0 );

		viewerTransform = new AffineTransform3D();

		renderTarget = new ThumbnailTarget( width, height );

		imageRenderer = new MultiResolutionRenderer( renderTarget, new PainterThread( null ), new double[] {
				1 }, 0, false, 1, null, false, new Cache.Dummy() );

		visibilityAndGrouping = new VisibilityAndGrouping( state );
		visibilityAndGrouping.addUpdateListener( this );

		transformListeners = new CopyOnWriteArrayList< TransformListener< AffineTransform3D > >();
	}

	public BufferedImage getBufferedImage()
	{
		return renderTarget.getBufferedImage();
	}

	public void paint( ViewerState state )
	{
		imageRenderer.paint( state );
	}

	/**
	 * Repaint as soon as possible.
	 */
	public void requestRepaint()
	{
		imageRenderer.requestRepaint();
	}

	@Override
	public synchronized void transformChanged( final AffineTransform3D transform )
	{
		viewerTransform.set( transform );
		state.setViewerTransform( transform );
		for ( final TransformListener< AffineTransform3D > l : transformListeners )
			l.transformChanged( viewerTransform );
		requestRepaint();
	}

	@Override
	public void visibilityChanged( final VisibilityAndGrouping.Event e )
	{
		switch ( e.id )
		{
			case CURRENT_SOURCE_CHANGED:
				requestRepaint();
				break;
			case DISPLAY_MODE_CHANGED:
				requestRepaint();
				break;
			case GROUP_NAME_CHANGED:
				requestRepaint();
				break;
			case VISIBILITY_CHANGED:
				requestRepaint();
				break;
		}
	}

	private final static double c = Math.cos( Math.PI / 4 );

	/**
	 * Switch to next interpolation mode. (Currently, there are two
	 * interpolation modes: nearest-neighbor and N-linear.
	 */
	public synchronized void toggleInterpolation()
	{
		final Interpolation interpolation = state.getInterpolation();
		if ( interpolation == Interpolation.NEARESTNEIGHBOR )
		{
			state.setInterpolation( Interpolation.NLINEAR );
		}
		else
		{
			state.setInterpolation( Interpolation.NEARESTNEIGHBOR );
		}
		requestRepaint();
	}

	/**
	 * Set the viewer transform.
	 */
	public synchronized void setCurrentViewerTransform( final AffineTransform3D viewerTransform )
	{
		transformChanged( viewerTransform );
	}

	/**
	 * Get a copy of the current {@link ViewerState}.
	 *
	 * @return a copy of the current {@link ViewerState}.
	 */
	public synchronized ViewerState getState()
	{
		return state.copy();
	}

	/**
	 * Add a {@link TransformListener} to notify about viewer transformation
	 * changes. Listeners will be notified <em>before</em> calling
	 * {@link #requestRepaint()} so they have the chance to interfere.
	 *
	 * @param listener
	 *            the transform listener to add.
	 * @param index
	 *            position in the list of listeners at which to insert this one.
	 */
	public void addTransformListener( final TransformListener< AffineTransform3D > listener, final int index )
	{
		synchronized ( transformListeners )
		{
			final int s = transformListeners.size();
			transformListeners.add( index < 0 ? 0 : index > s ? s : index, listener );
			listener.transformChanged( viewerTransform );
		}
	}

	public synchronized void stateFromXml( final Element parent )
	{
		final XmlIoViewerState io = new XmlIoViewerState();
		io.restoreFromXml( parent.getChild( io.getTagName() ), state );
	}

	public int getWidth()
	{
		return width;
	}

	public int getHeight()
	{
		return height;
	}

	public class ThumbnailTarget implements RenderTarget
	{
		private BufferedImage bi;
		int width, height;

		public ThumbnailTarget( int width, int height )
		{
			this.width = width;
			this.height = height;
		}

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

		public BufferedImage getBufferedImage()
		{
			return bi;
		}
	}
}
