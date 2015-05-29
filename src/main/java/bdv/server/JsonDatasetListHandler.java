package bdv.server;

import bdv.model.DataSet;
import com.google.gson.stream.JsonWriter;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

/**
 * Provides a list of available datasets on this {@link BigDataServer}
 *
 * @author HongKee Moon <moon@mpi-cbg.de>
 */
public class JsonDatasetListHandler extends ContextHandler
{
	private final Server server;

	public JsonDatasetListHandler( final Server server, final ContextHandlerCollection handlers ) throws IOException, URISyntaxException
	{
		this.server = server;
		setContextPath( "/" + Constants.DATASETLIST_CONTEXT_NAME );
	}

	@Override
	public void doHandle( final String target, final Request baseRequest, final HttpServletRequest request, final HttpServletResponse response ) throws IOException, ServletException
	{
		list( baseRequest, response );
	}

	private void list( final Request baseRequest, final HttpServletResponse response ) throws IOException
	{
		response.setContentType( "application/json" );
		response.setStatus( HttpServletResponse.SC_OK );
		baseRequest.setHandled( true );

		final PrintWriter ow = response.getWriter();
		getJsonDatasetList( ow );
		ow.close();
	}

	private void getJsonDatasetList( final PrintWriter out ) throws IOException
	{
		final JsonWriter writer = new JsonWriter( out );

		writer.setIndent( "\t" );

		writer.beginObject();

		getContexts( writer );

		writer.endObject();

		writer.flush();

		writer.close();
	}

	private String getContexts( final JsonWriter writer ) throws IOException
	{
		final ArrayList<DataSet> list = new ArrayList<>();

		for ( final Handler handler : server.getChildHandlersByClass( CellHandler.class ) )
		{
			CellHandler contextHandler = null;
			if ( handler instanceof CellHandler )
			{
				contextHandler = ( CellHandler ) handler;

				if ( contextHandler.isActive() )
				{
					list.add( contextHandler.getDataSet() );

				}
			}
		}

		// Sort the list by Category and Index
		Collections.sort( list, new Comparator< DataSet >()
		{
			@Override
			public int compare( final DataSet lhs, DataSet rhs )
			{
				// return 1 if rhs should be before lhs
				// return -1 if lhs should be before rhs
				// return 0 otherwise
				if( lhs.getCategory().equals( rhs.getCategory() ))
				{
					return lhs.getIndex().compareToIgnoreCase( rhs.getIndex() );
				}
				else
				{
					return lhs.getCategory().compareToIgnoreCase( rhs.getCategory() );
				}
			}
		} );

		// Buld json list
		final StringBuilder sb = new StringBuilder();
		for(DataSet ds : list)
		{
			writer.name( ds.getName() ).beginObject();

			writer.name( "id" ).value( ds.getName() );

			writer.name( "category" ).value( ds.getCategory() );

			writer.name( "description" ).value( ds.getDescription() );

			writer.name( "index" ).value( ds.getIndex() );

			writer.name( "thumbnailUrl" ).value( ds.getThumbnailUrl() );

			writer.name( "datasetUrl" ).value( ds.getDatasetUrl() );

			writer.endObject();
		}

		return sb.toString();
	}
}
