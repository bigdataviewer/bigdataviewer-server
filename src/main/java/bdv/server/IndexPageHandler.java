package bdv.server;

import bdv.model.DataSet;
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
 * Provides the default index page of available datasets on this {@link BigDataServer}
 * @author HongKee Moon <moon@mpi-cbg.de>
 */
public class IndexPageHandler extends ContextHandler
{
	private final Server server;

	public IndexPageHandler( final Server server, final ContextHandlerCollection handlers ) throws IOException, URISyntaxException
	{
		this.server = server;
		setContextPath( "/" );
	}

	@Override
	public void doHandle( final String target, final Request baseRequest, final HttpServletRequest request, final HttpServletResponse response ) throws IOException, ServletException
	{
		list( baseRequest, response );
	}

	private void list( final Request baseRequest, final HttpServletResponse response ) throws IOException
	{
		response.setContentType( "text/html" );
		response.setStatus( HttpServletResponse.SC_OK );
		baseRequest.setHandled( true );

		final PrintWriter ow = response.getWriter();
		getHtmlDatasetList( ow );
		ow.close();
	}

	private void getHtmlDatasetList( final PrintWriter out ) throws IOException
	{
		final ArrayList< DataSet > list = new ArrayList<>();

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
				if ( lhs.getCategory().equals( rhs.getCategory() ) )
				{
					return lhs.getIndex().compareToIgnoreCase( rhs.getIndex() );
				}
				else
				{
					return lhs.getCategory().compareToIgnoreCase( rhs.getCategory() );
				}
			}
		} );

		// Build html table for dataset list
		final StringBuilder sb = new StringBuilder();
		sb.append( "<!DOCTYPE html>\n" );
		sb.append( "<html lang='en'>\n" );
		sb.append( "<head><link href=\"https://maxcdn.bootstrapcdn.com/bootstrap/3.3.6/css/bootstrap.min.css\" rel=\"stylesheet\"></head>\n" );
		sb.append( "<body>\n" );

		sb.append( "<div class='container'>\n" );
		sb.append( "<h2>BigDataServer DataSet list</h2>" );
		sb.append( "<table class='table table-hover table-bordered'>\n" );

		sb.append( "<thead>\n" );
		sb.append( "<tr>\n" );
		sb.append( "<th>DataSet</th>\n" );
		sb.append( "<th>Description</th>\n" );
		sb.append( "</tr>\n" );
		sb.append( "</thead>\n" );

		for ( DataSet ds : list )
		{
			sb.append( "<tr>\n" );
			sb.append( "\t<td>\n" );
			sb.append( "\t\t<img src='" + ds.getThumbnailUrl() + "'/>\n" );
			sb.append( "\t</td>\n" );
			sb.append( "\t<td>\n" );
			sb.append( "\t\tCategory: " + ds.getCategory() + "<br/>\n" );
			sb.append( "\t\tName: " + ds.getName() + "<br/>\n" );
			sb.append( "\t\tDescription: " + ds.getDescription() + "<br/>\n" );
			sb.append( "\t</td>\n" );
			sb.append( "</tr>\n" );
		}
		sb.append( "</table>\n" );
		sb.append( "</div>\n" );
		sb.append( "</body>\n</html>" );

		out.write( sb.toString() );
		out.close();
	}
}