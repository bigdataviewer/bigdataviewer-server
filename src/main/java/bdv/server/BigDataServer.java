package bdv.server;

import org.eclipse.jetty.server.Server;

import java.util.HashMap;

public class BigDataServer
{
	static HashMap< String, String > dataSet = new HashMap<>();

	public static void main( final String[] args ) throws Exception
	{
		final String fn = args.length > 0 ? args[ 0 ] : "/Users/moon/Projects/git-projects/BigDataViewer/data/HisYFP-SPIM.xml";

		dataSet.put( "HisYFP-SPIM", fn );

		final int port = args.length > 1 ? Integer.parseInt( args[ 1 ] ) : 8080;
		System.setProperty( "org.eclipse.jetty.util.log.class", "org.eclipse.jetty.util.log.StdErrLog" );
		final Server server = new Server( port );

		String baseURL = "http://" + server.getURI().getHost() + ":" + port + "/";

//		System.out.println(baseURL);

		server.setHandler( new DataSetHandler( baseURL, dataSet ) );
		server.start();
		server.join();
	}
}
