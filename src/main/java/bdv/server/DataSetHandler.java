package bdv.server;

import mpicbg.spim.data.SpimDataException;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.HashMap;

public class DataSetHandler extends AbstractHandler
{
	final private HashMap< String, CellHandler > cellHandlers;

	public DataSetHandler( String baseURL, HashMap< String, String > ds ) throws SpimDataException, MalformedURLException
	{
		cellHandlers = new HashMap<>();

		for ( String dataSet : ds.keySet() )
		{
			cellHandlers.put( dataSet, new CellHandler( baseURL + dataSet + "/", ds.get( dataSet ) ) );
		}
	}

	@Override
	public void handle( final String target, final Request baseRequest, final HttpServletRequest request, final HttpServletResponse response ) throws IOException, ServletException
	{
		String dsName = target.replace( "/", "" );

		if ( cellHandlers.containsKey( dsName ) )
		{
			if ( request.getParameter( "p" ) != null )
				cellHandlers.get( dsName ).handle( target, baseRequest, request, response );
			else
				// Provide XML file
				cellHandlers.get( dsName ).provideXML( baseRequest, response );
		}
		else
		{
			return;
		}
	}
}
