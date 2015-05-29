package bdv.model;

/**
 * DataSet class holds SPIM dataset information
 */
public class DataSet
{
	/**
	 * DataSet Context name of this {@link bdv.server.CellHandler} is serving.
	 */
	private final String name;

	/**
	 * Full path of the dataset xml file this {@link bdv.server.CellHandler} is serving.
	 */
	private final String xmlPath;

	private String category;
	private String description;
	private String index;
	private long size;

	private String thumbnailUrl;
	private String datasetUrl;

	/**
	 * Instantiates a new DataSet
	 *
	 * @param name the dataset Context name
	 * @param xmlPath the dataset XML file path
	 * @param category the category of the dataset
	 * @param description the description of the dataset
	 * @param index the index of the dataset
	 */
	public DataSet( String name, String xmlPath, String category, String description, String index )
	{
		this.name = name;
		this.xmlPath = xmlPath;
		this.category = category;
		this.description = description;
		this.index = index;
	}

	/**
	 * Gets the dataset name.
	 *
	 * @return the dataset name
	 */
	public String getName()
	{
		return name;
	}

	/**
	 * Gets the dataset xml file path.
	 *
	 * @return the xml file path
	 */
	public String getXmlPath()
	{
		return xmlPath;
	}

	/**
	 * Gets the dataset category.
	 *
	 * @return the dataset category name
	 */
	public String getCategory()
	{
		return category;
	}

	/**
	 * Sets the dataset category name.
	 *
	 * @param category the category name
	 */
	public void setCategory( String category )
	{
		this.category = category;
	}

	/**
	 * Gets description.
	 *
	 * @return the description
	 */
	public String getDescription()
	{
		return description;
	}

	/**
	 * Sets description.
	 *
	 * @param description the description
	 */
	public void setDescription( String description )
	{
		this.description = description;
	}

	/**
	 * Gets index.
	 *
	 * @return the index
	 */
	public String getIndex()
	{
		return index;
	}

	/**
	 * Sets index.
	 *
	 * @param index the index
	 */
	public void setIndex( String index )
	{
		this.index = index;
	}

	/**
	 * Gets dataset size.
	 *
	 * @return the dataset size
	 */
	public long getSize()
	{
		return size;
	}

	/**
	 * Sets dataset size.
	 *
	 * @param size the dataset size
	 */
	public void setSize( long size )
	{
		this.size = size;
	}

	/**
	 * Gets thumbnail url.
	 *
	 * @return the thumbnail url
	 */
	public String getThumbnailUrl()
	{
		return datasetUrl + "png";
	}

	/**
	 * Gets dataset url.
	 *
	 * @return the dataset url
	 */
	public String getDatasetUrl()
	{
		return datasetUrl;
	}

	/**
	 * Sets dataset url.
	 *
	 * @param datasetUrl the dataset url
	 */
	public void setDatasetUrl( String datasetUrl )
	{
		this.datasetUrl = datasetUrl;
	}
}
