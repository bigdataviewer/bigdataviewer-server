package bdv.server;

import mpicbg.spim.data.SpimDataException;
import org.apache.commons.cli.*;
import org.apache.commons.lang.StringUtils;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.ConnectorStatistics;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.handler.RequestLogHandler;
import org.eclipse.jetty.server.handler.StatisticsHandler;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;

public class BigDataServer
{
	static HashMap< String, String > dataSet = new HashMap<>();

	private static final org.eclipse.jetty.util.log.Logger LOG = Log.getLogger( BigDataServer.class );

	static int port;

	static String serverName;

	public static void main( final String[] args ) throws Exception
	{
		if ( !processOptions( args ) )
			return;

		System.setProperty( "org.eclipse.jetty.util.log.class", "org.eclipse.jetty.util.log.StdErrLog" );

		// Threadpool for multiple connections
		final Server server = new Server( new QueuedThreadPool( 1000, 10 ) );

		// ServerConnector configuration
		final ServerConnector connector = new ServerConnector( server );
		connector.setHost( serverName );
		connector.setPort( port );
		LOG.info( "Set connectors: " + connector );
		server.setConnectors( new Connector[] { connector } );

		// Add Statistics bean to the connector
		final ConnectorStatistics connectorStats = new ConnectorStatistics();
		connector.addBean( connectorStats );

		final String baseURL = "http://" + server.getURI().getHost() + ":" + port;
		LOG.info( "Server Base URL: " + baseURL );

		// Handler initialization
		final StatisticsHandler statHandler = new StatisticsHandler();

		final HandlerCollection handlers = new HandlerCollection();

		final ContextHandlerCollection datasetHandlers = createHandlers( baseURL, dataSet );
		handlers.addHandler( datasetHandlers );
		handlers.addHandler( new ManagerHandler( baseURL, server, connectorStats, statHandler, datasetHandlers ) );
		handlers.addHandler( new RequestLogHandler() );
		handlers.addHandler( new JsonHandler( server, datasetHandlers ) );

		statHandler.setHandler( handlers );

		LOG.info( "Set handler: " + statHandler );
		server.setHandler( statHandler );
		LOG.info( "BigDataServer starting" );
		server.start();
		server.join();
	}

	static private boolean processOptions( String[] args ) throws ParseException, IOException
	{
		// create Options object
		Options options = new Options();

		// add p option
		options.addOption( "p", true, "listening port" );

		// add s baseurl
		options.addOption( "s", true, "server name" );

		// -d or multiple {name name.xml} pairs
		options.addOption( "d", true, "dataset file" );

		CommandLineParser parser = new BasicParser();
		CommandLine cmd = parser.parse( options, args );

		// Getting port number option
		String portString = cmd.getOptionValue( "p", "8080" );
		port = Integer.parseInt( portString );

		// Getting server name option
		String serverString = cmd.getOptionValue( "s", "localhost" );
		serverName = serverString;

		String datasetFile = cmd.getOptionValue( "d" );
		if ( cmd.hasOption( "d" ) )
		{
			// process the file given with "-d"

			// check the file presence
			Path path = Paths.get( datasetFile );

			if ( Files.notExists( path ) )
			{
				LOG.warn( "Dataset list file does not exist. Cannot start BigDataServer." );
				return false;
			}
			else
			{
				// Process dataset list file
				List< String > lines = Files.readAllLines( path, StandardCharsets.UTF_8 );

				for ( String str : lines )
				{
					String[] tokens = str.split( "\t" );
					if ( StringUtils.isNotEmpty( tokens[ 0 ].trim() ) && StringUtils.isNotEmpty( tokens[ 1 ].trim() ) )
					{
						dataSet.put( tokens[ 0 ].trim(), tokens[ 1 ].trim() );
						LOG.info( "Dataset added: {" + tokens[ 0 ].trim() + ", " + tokens[ 1 ].trim() + "}" );

						if ( Files.notExists( Paths.get( tokens[ 1 ].trim() ) ) )
						{
							LOG.warn( "Dataset file does not exist: \"" + tokens[ 1 ].trim() + "\". Cannot start BigDataServer." );
							return false;
						}
					}
					else
					{
						LOG.warn( "Invalid dataset entry (will be skipped): {" + str + "}" );
					}
				}
			}
		}
		else
		{
			String keyHolder = null;

			// process {name, name.xml} pairs
			for ( Object s : cmd.getArgList() )
			{
				if ( keyHolder != null )
				{
					dataSet.put( keyHolder, s.toString() );
					LOG.info( "Dataset added: {" + keyHolder + ", " + s.toString() + "}" );
					keyHolder = null;
				}
				else
				{
					keyHolder = s.toString();
				}
			}

			if ( keyHolder != null )
			{
				LOG.warn( "Dataset list has an error while processing. Cannot start BigDataServer." );
				return false;
			}
		}

		return true;
	}

	static private ContextHandlerCollection createHandlers( final String baseURL, final HashMap< String, String > dataSet ) throws SpimDataException
	{
		final ContextHandlerCollection handlers = new ContextHandlerCollection();

		for ( final String key : dataSet.keySet() )
		{
			final String context = "/" + key;
			final CellHandler ctx = new CellHandler( baseURL + context + "/", dataSet.get( key ) );
			ctx.setContextPath( context );
			handlers.addHandler( ctx );
		}

		return handlers;
	}
}
