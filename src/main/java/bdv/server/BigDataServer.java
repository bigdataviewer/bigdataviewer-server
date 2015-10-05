package bdv.server;

import bdv.model.DataSet;
import mpicbg.spim.data.SpimDataException;
import org.apache.commons.cli.*;
import org.apache.commons.lang.StringUtils;
import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.server.handler.*;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.security.Constraint;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/**
 * Serve XML/HDF5 datasets over HTTP.
 *
 * <pre>
 * usage: BigDataServer [OPTIONS] [NAME XML]...
 * Serves one or more XML/HDF5 datasets for remote access over HTTP.
 * Provide (NAME XML) pairs on the command line or in a dataset file, where
 * NAME is the name under which the dataset should be made accessible and XML
 * is the path to the XML file of the dataset.
 *  -d &lt;FILE&gt;       Dataset file: A plain text file specifying one dataset
 *                  per line. Each line is formatted as "NAME &lt;TAB&gt; XML".
 *  -p &lt;PORT&gt;       Listening port. (default: 8080)
 *  -s &lt;HOSTNAME&gt;   Hostname of the server.
 *  -t &lt;DIRECTORY&gt;  Directory to store thumbnails. (new temporary directory
 *                  by default.)
 *  -m              Enable statistics and manager context.
 *  -mp             Manager context HTTPS port.
 * </pre>
 *
 *
 * @author Tobias Pietzsch <tobias.pietzsch@gmail.com>
 * @author HongKee Moon <moon@mpi-cbg.de>
 */
public class BigDataServer
{
	private static final org.eclipse.jetty.util.log.Logger LOG = Log.getLogger( BigDataServer.class );

	static Parameters getDefaultParameters()
	{
		final int port = 8080;
		final int sslPort = 8443;
		String hostname;
		try
		{
			hostname = InetAddress.getLocalHost().getHostName();
		}
		catch ( final UnknownHostException e )
		{
			hostname = "localhost";
		}
		final String thumbnailDirectory = null;
		final boolean enableManagerContext = false;
		return new Parameters( port, sslPort, hostname, new HashMap< String, DataSet >(), thumbnailDirectory, enableManagerContext );
	}

	public static void main( final String[] args ) throws Exception
	{
		System.setProperty( "org.eclipse.jetty.util.log.class", "org.eclipse.jetty.util.log.StdErrLog" );

		final Parameters params = processOptions( args, getDefaultParameters() );
		if ( params == null )
			return;

		final String thumbnailsDirectoryName = getThumbnailDirectoryPath( params );

		// Threadpool for multiple connections
		final Server server = new Server( new QueuedThreadPool( 200, 8 ) );

		// HTTP Configuration
		HttpConfiguration httpConfig = new HttpConfiguration();
		httpConfig.setSecureScheme( "https" );
		httpConfig.setSecurePort( params.getSslport() );

		// Setup buffers on http
		HttpConnectionFactory httpConnectionFactory = new HttpConnectionFactory( httpConfig );
		httpConnectionFactory.setInputBufferSize( 64 * 1024 );

		// ServerConnector configuration
		final ServerConnector connector = new ServerConnector( server, httpConnectionFactory );
		connector.setHost( params.getHostname() );
		connector.setPort( params.getPort() );
		LOG.info( "Set connectors: " + connector );
		server.setConnectors( new Connector[] { connector } );
		final String baseURL = "http://" + server.getURI().getHost() + ":" + params.getPort();

		// Handler initialization
		final HandlerCollection handlers = new HandlerCollection();

		final ContextHandlerCollection datasetHandlers = createHandlers( baseURL, params.getDatasets(), thumbnailsDirectoryName );
		handlers.addHandler( datasetHandlers );
		handlers.addHandler( new JsonDatasetListHandler( server, datasetHandlers ) );

		Handler handler = handlers;
		if ( params.enableManagerContext() )
		{
			HttpConfiguration https = new HttpConfiguration();
			https.addCustomizer( new SecureRequestCustomizer() );

			// Please, change localhost according to your site name
			// keytool -genkey -alias localhost -keyalg RSA -keystore keystore.jks -keysize 2048
			SslContextFactory sslContextFactory = new SslContextFactory();
			sslContextFactory.setKeyStorePath( Resource.newClassPathResource( "etc/keystore.jks" ).toString() );
			sslContextFactory.setKeyStorePassword( "123456" );
			sslContextFactory.setKeyManagerPassword( "123456" );

			ServerConnector sslConnector = new ServerConnector( server,
					new SslConnectionFactory( sslContextFactory, "http/1.1" ),
					new HttpConnectionFactory( https ) );
			sslConnector.setHost( params.getHostname() );
			sslConnector.setPort( params.getSslport() );

			server.addConnector( sslConnector );

			// Add Statistics bean to the connector
			final ConnectorStatistics connectorStats = new ConnectorStatistics();
			connector.addBean( connectorStats );

			// create StatisticsHandler wrapper and ManagerHandler
			final StatisticsHandler statHandler = new StatisticsHandler();
			handlers.addHandler( new ManagerHandler( baseURL, server, connectorStats, statHandler, datasetHandlers, thumbnailsDirectoryName ) );
			statHandler.setHandler( handlers );

			Constraint constraint = new Constraint();
			constraint.setName( Constraint.__BASIC_AUTH );
			constraint.setRoles( new String[] { "admin", "superuser" } );
			constraint.setAuthenticate( true );
			// 2 means CONFIDENTIAL. 1 means INTEGRITY
			constraint.setDataConstraint( Constraint.DC_CONFIDENTIAL );

			ConstraintMapping cm = new ConstraintMapping();
			cm.setPathSpec( "/manager/*" );
			cm.setConstraint( constraint );

			// Please change the password in realm.properties
			HashLoginService loginService = new HashLoginService( "BigDataServerRealm", Resource.newClassPathResource( "etc/realm.properties" ).toString() );
			server.addBean( loginService );

			HandlerList handlerList = new HandlerList();

			ContextHandler redirectHandler = new ContextHandler();
			redirectHandler.setContextPath( "/" + Constants.MANAGER_CONTEXT_NAME );
			redirectHandler.setHandler( new SecuredRedirectHandler() );

			handlerList.addHandler( redirectHandler );

			ConstraintSecurityHandler sh = new ConstraintSecurityHandler();
			sh.setLoginService( loginService );
			sh.setAuthenticator( new BasicAuthenticator() );
			sh.addConstraintMapping( cm );
			sh.setHandler( statHandler );

			handlerList.addHandler( sh );

			handler = handlerList;
		}

		LOG.info( "Set handler: " + handler );
		server.setHandler( handler );
		LOG.info( "Server Base URL: " + baseURL );
		LOG.info( "BigDataServer starting" );
		server.start();
		server.join();
	}

	/**
	 * Server parameters: hostname, port, sslPort, datasets.
	 */
	private static class Parameters
	{
		private final int port;

		private final String hostname;

		private final int sslPort;

		/**
		 * maps from dataset name to dataset xml path.
		 */
		private final Map< String, DataSet > datasetNameToDataSet;

		private final String thumbnailDirectory;

		private final boolean enableManagerContext;

		Parameters( final int port, final int sslPort, final String hostname, final Map< String, DataSet > datasetNameToDataSet, final String thumbnailDirectory, final boolean enableManagerContext )
		{
			this.port = port;
			this.sslPort = sslPort;
			this.hostname = hostname;
			this.datasetNameToDataSet = datasetNameToDataSet;
			this.thumbnailDirectory = thumbnailDirectory;
			this.enableManagerContext = enableManagerContext;
		}

		public int getPort()
		{
			return port;
		}

		public String getHostname()
		{
			return hostname;
		}

		public int getSslport()
		{
			return sslPort;
		}

		public String getThumbnailDirectory()
		{
			return thumbnailDirectory;
		}

		/**
		 * Get datasets.
		 *
		 * @return datasets as a map from dataset name to dataset xml path.
		 */
		public Map< String, DataSet > getDatasets()
		{
			return datasetNameToDataSet;
		}

		public boolean enableManagerContext()
		{
			return enableManagerContext;
		}
	}

	@SuppressWarnings( "static-access" )
	static private Parameters processOptions( final String[] args, final Parameters defaultParameters ) throws IOException
	{
		// create Options object
		final Options options = new Options();

		final String cmdLineSyntax = "BigDataServer [OPTIONS] [NAME XML] ...\n";

		final String description =
				"Serves one or more XML/HDF5 datasets for remote access over HTTP.\n" +
						"Provide (NAME XML) pairs on the command line or in a dataset file, where NAME is the name under which the dataset should be made accessible and XML is the path to the XML file of the dataset.";

		options.addOption( OptionBuilder
				.withDescription( "Hostname of the server.\n(default: " + defaultParameters.getHostname() + ")" )
				.hasArg()
				.withArgName( "HOSTNAME" )
				.create( "s" ) );

		options.addOption( OptionBuilder
				.withDescription( "Listening port.\n(default: " + defaultParameters.getPort() + ")" )
				.hasArg()
				.withArgName( "PORT" )
				.create( "p" ) );

		// -d or multiple {name name.xml} pairs
		options.addOption( OptionBuilder
				.withDescription( "Dataset file: A plain text file specifying one dataset per line. Each line is formatted as \"NAME <TAB> XML\"." )
				.hasArg()
				.withArgName( "FILE" )
				.create( "d" ) );

		options.addOption( OptionBuilder
				.withDescription( "Directory to store thumbnails. (new temporary directory by default.)" )
				.hasArg()
				.withArgName( "DIRECTORY" )
				.create( "t" ) );

		options.addOption( OptionBuilder
				.withDescription( "Enable statistics and manager context." )
				.create( "m" ) );

		options.addOption( OptionBuilder
				.withDescription( "Manager context HTTPS port." + "\n(default: " + defaultParameters.getSslport() + ")" )
				.hasArg()
				.withArgName( "SECURE_PORT" )
				.create( "mp" ) );

		try
		{
			final CommandLineParser parser = new BasicParser();
			final CommandLine cmd = parser.parse( options, args );

			// Getting port number option
			final String portString = cmd.getOptionValue( "p", Integer.toString( defaultParameters.getPort() ) );
			final int port = Integer.parseInt( portString );

			// Getting server name option
			final String serverName = cmd.getOptionValue( "s", defaultParameters.getHostname() );

			// Getting thumbnail directory option
			final String thumbnailDirectory = cmd.getOptionValue( "t", defaultParameters.getThumbnailDirectory() );

			final HashMap< String, DataSet > datasets = new HashMap< String, DataSet >( defaultParameters.getDatasets() );

			boolean enableManagerContext = false;
			int sslPort = defaultParameters.getSslport();

			if ( cmd.hasOption( "m" ) )
			{
				enableManagerContext = true;

				final String securePortString = cmd.getOptionValue( "mp", Integer.toString( defaultParameters.getSslport() ) );
				sslPort = Integer.parseInt( securePortString );

				if ( !cmd.hasOption( "d" ) )
					throw new IllegalArgumentException( "Dataset list file is necessary for BigDataServer manager" );
			}

			// Path for holding the dataset file
			if ( cmd.hasOption( "d" ) )
			{
				// process the file given with "-d"
				final String datasetFile = cmd.getOptionValue( "d" );

				// check the file presence
				final Path path = Paths.get( datasetFile );

				if ( Files.notExists( path ) )
					throw new IllegalArgumentException( "Dataset list file does not exist." );

				readDatasetFile( datasets, path );
			}

			// process additional {name, name.xml} pairs given on the
			// command-line
			final String[] leftoverArgs = cmd.getArgs();
			if ( leftoverArgs.length % 2 != 0 )
				throw new IllegalArgumentException( "Dataset list has an error while processing." );

			for ( int i = 0; i < leftoverArgs.length; i += 2 )
			{
				final String name = leftoverArgs[ i ];
				final String xmlpath = leftoverArgs[ i + 1 ];
				tryAddDataset( datasets, name, xmlpath );
			}

			if ( datasets.isEmpty() )
				throw new IllegalArgumentException( "Dataset list is empty." );

			return new Parameters( port, sslPort, serverName, datasets, thumbnailDirectory, enableManagerContext );
		}
		catch ( final ParseException | IllegalArgumentException e )
		{
			LOG.warn( e.getMessage() );
			System.out.println();
			final HelpFormatter formatter = new HelpFormatter();
			formatter.printHelp( cmdLineSyntax, description, options, null );
		}
		return null;
	}

	private static void readDatasetFile( final HashMap< String, DataSet > datasets, final Path path ) throws IOException
	{
		// Process dataset list file
		DataSet.setDataSetListPath( path );
		final List< String > lines = Files.readAllLines( path, StandardCharsets.UTF_8 );

		for ( final String str : lines )
		{
			final String[] tokens = str.split( "\\s*\\t\\s*" );
			if ( tokens.length >= 2 && StringUtils.isNotEmpty( tokens[ 0 ].trim() ) && StringUtils.isNotEmpty( tokens[ 1 ].trim() ) )
			{
				final String name = tokens[ 0 ].trim();
				final String xmlpath = tokens[ 1 ].trim();

				if ( tokens.length == 2 )
				{
					tryAddDataset( datasets, name, xmlpath );
				}
				else if ( tokens.length == 5 )
				{
					final String category = tokens[ 2 ].trim();
					final String desc = tokens[ 3 ].trim();
					final String index = tokens[ 4 ].trim();

					tryAddDataset( datasets, name, xmlpath, category, desc, index );
				}
			}
			else
			{
				LOG.warn( "Invalid dataset file line (will be skipped): {" + str + "}" );
			}
		}
	}

	private static void tryAddDataset( final HashMap< String, DataSet > datasetNameToDataSet, final String ... args ) throws IllegalArgumentException
	{
		if ( args.length >= 2)
		{
			final String name = args[0];
			final String xmlpath = args[1];

			for ( final String reserved : Constants.RESERVED_CONTEXT_NAMES )
				if ( name.equals( reserved ) )
					throw new IllegalArgumentException( "Cannot use dataset name: \"" + name + "\" (reserved for internal use)." );
			if ( datasetNameToDataSet.containsKey( name ) )
				throw new IllegalArgumentException( "Duplicate dataset name: \"" + name + "\"" );
			if ( Files.notExists( Paths.get( xmlpath ) ) )
				throw new IllegalArgumentException( "Dataset file does not exist: \"" + xmlpath + "\"" );

			String category = "";
			String desc = "";
			String index = "";

			if( args.length == 5 )
			{
				category = args[2];
				desc = args[3];
				index = args[4];
			}

			DataSet ds = new DataSet( name, xmlpath, category, desc, index );
			datasetNameToDataSet.put( name, ds );
			LOG.info( "Dataset added: {" + name + ", " + xmlpath + "}" );
		}
	}

	private static String getThumbnailDirectoryPath( final Parameters params ) throws IOException
	{
		final String thumbnailDirectoryName = params.getThumbnailDirectory();
		if ( thumbnailDirectoryName != null )
		{
			Path thumbnails = Paths.get( thumbnailDirectoryName );
			if ( ! Files.exists( thumbnails ) )
			{
				try
				{
					thumbnails = Files.createDirectories( thumbnails );
					return thumbnails.toFile().getAbsolutePath();
				}
				catch ( final IOException e )
				{
					LOG.warn( e.getMessage() );
					LOG.warn( "Could not create thumbnails directory \"" + thumbnailDirectoryName + "\".\n Trying to create temporary directory." );
				}
			}
			else
			{
				if ( ! Files.isDirectory( thumbnails ) )
					LOG.warn( "Thumbnails directory \"" + thumbnailDirectoryName + "\" is not a directory.\n Trying to create temporary directory." );
				else
					return thumbnails.toFile().getAbsolutePath();
			}
		}
		final Path thumbnails = Files.createTempDirectory( "thumbnails" );
		thumbnails.toFile().deleteOnExit();
		return thumbnails.toFile().getAbsolutePath();
	}

	private static ContextHandlerCollection createHandlers( final String baseURL, final Map< String, DataSet > dataSet, final String thumbnailsDirectoryName ) throws SpimDataException, IOException
	{
		final ContextHandlerCollection handlers = new ContextHandlerCollection();

		for ( final Entry< String, DataSet > entry : dataSet.entrySet() )
		{
			final String name = entry.getKey();
			final DataSet ds = entry.getValue();
			final String context = "/" + name;
			final CellHandler ctx = new CellHandler( baseURL + context + "/", ds, thumbnailsDirectoryName );
			ctx.setContextPath( context );
			handlers.addHandler( ctx );
		}

		return handlers;
	}
}
