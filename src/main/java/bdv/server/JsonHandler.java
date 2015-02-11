package bdv.server;

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

/**
 * Provides a list of available datasets on this {@link BigDataServer}
 *
 * @author HongKee Moon <moon@mpi-cbg.de>
 */
public class JsonHandler extends ContextHandler
{
	private final Server server;

	public JsonHandler( final Server server, final ContextHandlerCollection handlers ) throws IOException, URISyntaxException
	{
		this.server = server;
		setContextPath( "/json" );
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
		final StringBuilder sb = new StringBuilder();
		for ( final Handler handler : server.getChildHandlersByClass( CellHandler.class ) )
		{
			CellHandler contextHandler = null;
			if ( handler instanceof CellHandler )
			{
				contextHandler = ( CellHandler ) handler;

				final String datasetName = contextHandler.getContextPath().replaceFirst( "/", "" );

				writer.name( datasetName ).beginObject();

				writer.name( "id" ).value( datasetName );

				//writer.name( "desc" ).value( contextHandler.getDescription() );
				writer.name( "description" ).value( "NotImplemented" );

				writer.name( "thumbnailUrl" ).value( contextHandler.getThumbnailUrl() );

				writer.name( "datasetUrl" ).value( contextHandler.getDataSetURL() );

				writer.endObject();
			}
		}
		return sb.toString();
	}
}
