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
import org.eclipse.jetty.util.security.Constraint;
import org.eclipse.jetty.util.security.Password;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import sun.security.x509.AlgorithmId;
import sun.security.x509.CertificateAlgorithmId;
import sun.security.x509.CertificateIssuerName;
import sun.security.x509.CertificateSerialNumber;
import sun.security.x509.CertificateSubjectName;
import sun.security.x509.CertificateValidity;
import sun.security.x509.CertificateVersion;
import sun.security.x509.CertificateX509Key;
import sun.security.x509.X500Name;
import sun.security.x509.X509CertImpl;
import sun.security.x509.X509CertInfo;

import java.io.BufferedWriter;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.SignatureException;
import java.security.cert.CertificateException;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 *  -m &lt;SECURE_PORT&gt;Manager context HTTPS port. The manager context is automatically enabled.
 *                  (default: 8443)
 *  -p &lt;PORT&gt;       Listening port.
 *                  (default: 8080)
 *  -s &lt;HOSTNAME&gt;   Hostname of the server.
 *  -t &lt;DIRECTORY&gt;  Directory to store thumbnails. (new temporary directory
 *                  by default.)
 * </pre>
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
		final HttpConnectionFactory httpConnectionFactory = new HttpConnectionFactory( httpConfig );
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
		handlers.addHandler( new IndexPageHandler( server, datasetHandlers ) );

		Handler handler = handlers;
		if ( params.enableManagerContext() )
		{
			if ( !checkKeystore() )
				throw new IllegalArgumentException( "Keystore file does not exist." );

			if ( !checkRealmProperty() )
				throw new IllegalArgumentException( "Login property file does not exist." );

			final HttpConfiguration https = new HttpConfiguration();
			https.addCustomizer( new SecureRequestCustomizer() );

			final SslContextFactory sslContextFactory = new SslContextFactory();
			sslContextFactory.setKeyStorePath( "etc/keystore.jks" );

			final char passwordArray[] = System.console().readPassword( "Please, enter your keystore password: " );
			String password = new String( passwordArray );
			sslContextFactory.setKeyStorePassword( password );
			sslContextFactory.setKeyManagerPassword( password );

			final ServerConnector sslConnector = new ServerConnector( server,
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

			final Constraint constraint = new Constraint();
			constraint.setName( Constraint.__BASIC_AUTH );
			constraint.setRoles( new String[] { "admin" } );
			constraint.setAuthenticate( true );
			// 2 means CONFIDENTIAL. 1 means INTEGRITY
			constraint.setDataConstraint( Constraint.DC_CONFIDENTIAL );

			final ConstraintMapping cm = new ConstraintMapping();
			cm.setPathSpec( "/" + Constants.MANAGER_CONTEXT_NAME + "/*" );
			cm.setConstraint( constraint );

			// Please change the password in realm.properties
			final HashLoginService loginService = new HashLoginService( "BigDataServerRealm", "etc/realm.properties" );
			server.addBean( loginService );

			final ConstraintSecurityHandler sh = new ConstraintSecurityHandler();
			sh.setLoginService( loginService );
			sh.setAuthenticator( new BasicAuthenticator() );
			sh.addConstraintMapping( cm );
			sh.setHandler( statHandler );

			final HandlerList handlerList = new HandlerList();
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

	private static boolean checkKeystore()
	{
		// check if "etc/keystore.jks" exists
		if ( Files.exists( Paths.get( "etc/keystore.jks" ) ) )
			return true;
		else
		{
			try
			{
				// keytool -genkey -alias localhost -keyalg RSA -keystore keystore.jks -keysize 2048
				if ( Files.notExists( Paths.get( "etc/" ) ) )
					Files.createDirectory( Paths.get( "etc/" ) );

				KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance( "RSA" );
				keyPairGenerator.initialize( 2048 );
				KeyPair KPair = keyPairGenerator.generateKeyPair();
				PrivateKey privkey = KPair.getPrivate();

				X509CertInfo info = new X509CertInfo();
				Date from = new Date();
				Date to = new Date( from.getTime() + 365 * 86400000l );
				CertificateValidity interval = new CertificateValidity( from, to );
				BigInteger sn = new BigInteger( 64, new SecureRandom() );
				X500Name owner = new X500Name( "CN=Unknown, L=Unknown, ST=Unknown, O=Unknown, OU=Unknown, C=Unknown" );

				info.set( X509CertInfo.VALIDITY, interval );
				info.set( X509CertInfo.SERIAL_NUMBER, new CertificateSerialNumber( sn ) );
				boolean justName = isJavaAtLeast( 1.8 );
				if ( justName )
				{
					info.set( X509CertInfo.SUBJECT, owner );
					info.set( X509CertInfo.ISSUER, owner );
				}
				else
				{
					info.set( X509CertInfo.SUBJECT, new CertificateSubjectName( owner ) );
					info.set( X509CertInfo.ISSUER, new CertificateIssuerName( owner ) );
				}

				info.set( X509CertInfo.KEY, new CertificateX509Key( KPair.getPublic() ) );
				info.set( X509CertInfo.VERSION, new CertificateVersion( CertificateVersion.V3 ) );
				AlgorithmId algo = new AlgorithmId( AlgorithmId.sha256WithRSAEncryption_oid );
				info.set( X509CertInfo.ALGORITHM_ID, new CertificateAlgorithmId( algo ) );

				// Sign the cert to identify the algorithm that's used.
				X509CertImpl cert = new X509CertImpl( info );
				cert.sign( privkey, "SHA256withRSA" );

				// Update the algorithm, and resign.
				algo = ( AlgorithmId ) cert.get( X509CertImpl.SIG_ALG );
				info.set( CertificateAlgorithmId.NAME + "." + CertificateAlgorithmId.ALGORITHM, algo );
				cert = new X509CertImpl( info );
				cert.sign( privkey, "SHA256withRSA" );

				KeyStore keyStore = null;
				FileOutputStream keyStoreFile = null;

				// Load the default Java keystore
				keyStore = KeyStore.getInstance( KeyStore.getDefaultType() );
				keyStore.load( null, "changeit".toCharArray() );

				final char passwordArray[] = System.console().readPassword( "Enter your new keystore password for SSL connection: " );

				String password = new String( passwordArray );

				// Put our information
				keyStore.setCertificateEntry( "localhost", cert );
				keyStore.setKeyEntry( "localhost", privkey,
						password.toCharArray(),
						new java.security.cert.Certificate[] { cert } );

				// Generate new cert
				keyStoreFile = new FileOutputStream( "etc/keystore.jks" );
				keyStore.store( keyStoreFile, password.toCharArray() );
				keyStoreFile.close();
			}
			catch ( FileNotFoundException e )
			{
				e.printStackTrace();
				return false;
			}
			catch ( KeyStoreException e )
			{
				e.printStackTrace();
				return false;
			}
			catch ( IOException e )
			{
				e.printStackTrace();
				return false;
			}
			catch ( NoSuchAlgorithmException e )
			{
				e.printStackTrace();
				return false;
			}
			catch ( CertificateException e )
			{
				e.printStackTrace();
				return false;
			}
			catch ( SignatureException e )
			{
				e.printStackTrace();
				return false;
			}
			catch ( NoSuchProviderException e )
			{
				e.printStackTrace();
				return false;
			}
			catch ( InvalidKeyException e )
			{
				e.printStackTrace();
				return false;
			}
		}
		return true;
	}

	public static final Pattern JAVA_VERSION = Pattern.compile( "([0-9]*.[0-9]*)(.*)?" );

	/**
	 * Checks whether the current Java runtime has a version equal or higher then the given one. As Java version are
	 * not double (because they can use more digits such as 1.8.0), this method extracts the two first digits and
	 * transforms it as a double.
	 * @param version the version
	 * @return {@literal true} if the current Java runtime is at least the specified one,
	 * {@literal false} if not or if the current version cannot be retrieve or is the retrieved version cannot be
	 * parsed as a double.
	 */
	public static boolean isJavaAtLeast( double version )
	{
		String javaVersion = System.getProperty( "java.version" );
		if ( javaVersion == null )
		{
			return false;
		}

		// if the retrieved version is one three digits, remove the last one.
		Matcher matcher = JAVA_VERSION.matcher( javaVersion );
		if ( matcher.matches() )
		{
			javaVersion = matcher.group( 1 );
		}

		try
		{
			double v = Double.parseDouble( javaVersion );
			return v >= version;
		}
		catch ( NumberFormatException e )
		{
			return false;
		}
	}

	private static boolean checkRealmProperty()
	{
		Path path = Paths.get( "etc/realm.properties" );
		// check if "etc/realm.properties" exists
		if ( Files.exists( path ) )
			return true;
		else
		{
			try
			{
				if ( Files.notExists( Paths.get( "etc/" ) ) )
					Files.createDirectory( Paths.get( "etc/" ) );

				final String userId = System.console().readLine( "Enter your ID for manager : " );
				final char passwordArray[] = System.console().readPassword( "Enter your password for \"%s\": ", userId );

				String md5 = Password.MD5.digest( new String( passwordArray ) );

				BufferedWriter writer = Files.newBufferedWriter( path, StandardCharsets.UTF_8 );

				writer.append( "#   http://www.eclipse.org/jetty/documentation/current/configuring-security-secure-passwords.html\n" );
				writer.append( "#   Please, use obfuscated/MD5/crypted password only by using below instructions\n" );
				writer.append( "#\n" );
				writer.append( "# \t$ export JETTY_VERSION=9.0.0.RC0\n" );
				writer.append( "# \t$ java -cp lib/jetty-util-$JETTY_VERSION.jar org.eclipse.jetty.util.security.Password username blahblah\n" );
				writer.append( "# \tOBF:20771x1b206z\n" );
				writer.append( "# \tMD5:639bae9ac6b3e1a84cebb7b403297b79\n" );
				writer.append( "# \tCRYPT:me/ks90E221EY\n" );

				writer.newLine();
				writer.append( userId );
				writer.append( ": " );
				writer.append( md5 );
				writer.append( ", admin" );
				writer.newLine();

				writer.flush();
				writer.close();
			}
			catch ( IOException e )
			{
				e.printStackTrace();
				return false;
			}
		}
		return true;
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
				.withDescription( "Manager context HTTPS port. The manager context is automatically enabled." + "\n(default: " + defaultParameters.getSslport() + ")" )
				.hasArg()
				.withArgName( "SECURE_PORT" )
				.create( "m" ) );

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

				final String securePortString = cmd.getOptionValue( "m", Integer.toString( defaultParameters.getSslport() ) );
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

	private static void tryAddDataset( final HashMap< String, DataSet > datasetNameToDataSet, final String... args ) throws IllegalArgumentException
	{
		if ( args.length >= 2 )
		{
			final String name = args[ 0 ];
			final String xmlpath = args[ 1 ];

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

			if ( args.length == 5 )
			{
				category = args[ 2 ];
				desc = args[ 3 ];
				index = args[ 4 ];
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
			if ( !Files.exists( thumbnails ) )
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
				if ( !Files.isDirectory( thumbnails ) )
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
