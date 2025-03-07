/*
 * Copyright 2017
 * Ubiquitous Knowledge Processing (UKP) Lab
 * Technische Universität Darmstadt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.dkpro.core.api.io;

import de.tudarmstadt.ukp.dkpro.core.api.metadata.type.DocumentMetaData;
import eu.openminted.share.annotations.api.Component;
import eu.openminted.share.annotations.api.Parameters;
import eu.openminted.share.annotations.api.constants.OperationType;
import org.apache.commons.lang3.StringUtils;
import org.apache.uima.UimaContext;
import org.apache.uima.cas.CAS;
import org.apache.uima.cas.CASException;
import org.apache.uima.collection.CollectionException;
import org.apache.uima.fit.component.CasCollectionReader_ImplBase;
import org.apache.uima.fit.descriptor.ConfigurationParameter;
import org.apache.uima.fit.descriptor.ExternalResource;
import org.apache.uima.resource.ResourceInitializationException;
import org.apache.uima.util.Progress;
import org.apache.uima.util.ProgressImpl;
import org.dkpro.core.api.parameter.ComponentParameters;
import org.dkpro.core.api.resources.CompressionMethod;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.util.AntPathMatcher;
import org.texttechnologylab.annotation.DocumentModification;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Base class for collection readers that plan to access resources on the file system or in the
 * classpath or basically anywhere where Spring can resolve them. ANT-style patterns are supported
 * to include or exclude particular resources.
 * <p>
 * Example of a hypothetic <code>FooReader</code> that should read only files ending in
 * <code>.foo</code> from in the directory <code>foodata</code> or any subdirectory thereof:
 *
 * <pre>
 * CollectionReader reader = createReader(FooReader.class,
 *         FooReader.PARAM_LANGUAGE, &quot;en&quot;,
 *         FooReader.PARAM_SOURCE_LOCATION, &quot;some/path&quot;,
 *         FooReader.PARAM_PATTERNS, &quot;[+]foodata/*&#42;/*.foo&quot;);
 * </pre>
 * <p>
 * The list of resources returned is sorted, so for the same set of resources, they are always
 * returned in the same order.
 *
 * @see <a href="http://ant.apache.org/manual/dirtasks.html#patterns">Documentation of <b>ant</b>
 *      patterns</a>
 *
 * @since 1.0.6
 */
@Component(value = OperationType.READER)
@Parameters(
		exclude = {
				ResourceCollectionReaderBase.PARAM_SOURCE_LOCATION,
				ResourceCollectionReaderBase.PARAM_INCLUDE_HIDDEN,
				ResourceCollectionReaderBase.PARAM_USE_DEFAULT_EXCLUDES,
				ResourceCollectionReaderBase.PARAM_LOG_FREQ })
public abstract class ResourceCollectionReaderBase
extends CasCollectionReader_ImplBase
{
	protected static final String JAR_PREFIX = "jar:file:";

	public static final String INCLUDE_PREFIX = "[+]";
	public static final String EXCLUDE_PREFIX = "[-]";

	/**
	 * Location from which the input is read.
	 *
	 * @deprecated use {@link #PARAM_SOURCE_LOCATION}
	 */
	@Deprecated
	public static final String PARAM_PATH = ComponentParameters.PARAM_SOURCE_LOCATION;
	/**
	 * Location from which the input is read.
	 */
	public static final String PARAM_SOURCE_LOCATION = ComponentParameters.PARAM_SOURCE_LOCATION;
	@ConfigurationParameter(name = PARAM_SOURCE_LOCATION, mandatory = false)
	private String sourceLocation;

	/**
	 * Location from which the output is read, used for looking for already existing files.
	 */
	public static final String PARAM_TARGET_LOCATION = ComponentParameters.PARAM_TARGET_LOCATION;
	@ConfigurationParameter(name = PARAM_TARGET_LOCATION, mandatory = false)
	private String targetLocation;

	/**
	 * A set of Ant-like include/exclude patterns. A pattern starts with {@link #INCLUDE_PREFIX [+]}
	 * if it is an include pattern and with {@link #EXCLUDE_PREFIX [-]} if it is an exclude pattern.
	 * The wildcard <code>&#47;**&#47;</code> can be used to address any number of sub-directories.
	 * The wildcard {@code *} can be used to a address a part of a name.
	 */
	public static final String PARAM_PATTERNS = ComponentParameters.PARAM_PATTERNS;
	@ConfigurationParameter(name = PARAM_PATTERNS, mandatory = false)
	private String[] patterns;

	/**
	 * Use the default excludes.
	 */
	public static final String PARAM_USE_DEFAULT_EXCLUDES = "useDefaultExcludes";
	@ConfigurationParameter(name = PARAM_USE_DEFAULT_EXCLUDES, mandatory = true, defaultValue = "true")
	private boolean useDefaultExcludes;

	/**
	 * Include hidden files and directories.
	 */
	public static final String PARAM_INCLUDE_HIDDEN = "includeHidden";
	@ConfigurationParameter(name = PARAM_INCLUDE_HIDDEN, mandatory = true, defaultValue = "false")
	private boolean includeHidden;

	/**
	 * Name of optional configuration parameter that contains the language of the documents in the
	 * input directory. If specified, this information will be added to the CAS.
	 */
	public static final String PARAM_LANGUAGE = ComponentParameters.PARAM_LANGUAGE;
	@ConfigurationParameter(name = PARAM_LANGUAGE, mandatory = false)
	private String language;

	/**
	 * Name of optional external (UIMA) resource that contains the Locator for a (Spring)
	 * ResourcePatternResolver implementation for locating (spring) resources.
	 */
	public static final String KEY_RESOURCE_RESOLVER = "resolver";
	@ExternalResource(key = KEY_RESOURCE_RESOLVER, mandatory = false)
	private final ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

	/**
	 * The frequency with which read documents are logged.
	 * <p>
	 * Set to 0 or negative values to deactivate logging.
	 */
	public static final String PARAM_LOG_FREQ = "logFreq";
	@ConfigurationParameter(name = PARAM_LOG_FREQ, mandatory = true, defaultValue = "1")
	private int logFreq;

	/**
	 * The frequency with which read documents are logged.
	 * <p>
	 * Set to 0 or negative values to deactivate logging.
	 */
	public static final String PARAM_SORT_BY_SIZE= "sortBySize";
	@ConfigurationParameter(name = PARAM_SORT_BY_SIZE, mandatory = false, defaultValue = "false")
	private boolean sortBySize;

	/**
	 * Modification Metadata: User that commited this modification
	 */
	public static final String PARAM_DOC_MODIFICATION_USER = "docModificationUser";
	@ConfigurationParameter(name = PARAM_DOC_MODIFICATION_USER, mandatory = false)
	private String docModificationUser;

	/**
	 * Modification Metadata: comment that describes this modification
	 */
	public static final String PARAM_DOC_MODIFICATION_COMMENT = "docModificationComment";
	@ConfigurationParameter(name = PARAM_DOC_MODIFICATION_COMMENT, mandatory = false)
	private String docModificationComment;

	/**
	 * Check parent directory of source location to find existing files in target
	 * Only applies if targetLocation is set
	 */
	public static final String PARAM_CHECK_PARENT_OF_SOURCE_LOCATION_FOR_SKIP_FILES = "checkParentOfSourceLocationForSkipFiles";
	@ConfigurationParameter(name = PARAM_CHECK_PARENT_OF_SOURCE_LOCATION_FOR_SKIP_FILES, mandatory = false, defaultValue = "false")
	private boolean checkParentOfSourceLocationForSkipFiles;

	private boolean docModificationWritten;

	private int completed;
	private List<Resource> resources;
	private Iterator<Resource> resourceIterator;

	private ProgressMeter progress;


	protected static final Set<String> KNOWN_FILE_EXTENSIONS = new HashSet<>();

	@Override
	public void initialize(UimaContext aContext)
			throws ResourceInitializationException
	{
		super.initialize(aContext);

		docModificationWritten = false;

		// known file extensions to remove when checking for existing files
		KNOWN_FILE_EXTENSIONS.add(".txt");
		KNOWN_FILE_EXTENSIONS.add(".xmi");
		KNOWN_FILE_EXTENSIONS.add(".xml");
		KNOWN_FILE_EXTENSIONS.add(".tei");
		KNOWN_FILE_EXTENSIONS.add(".conll");
		KNOWN_FILE_EXTENSIONS.add(CompressionMethod.GZIP.getExtension());
		KNOWN_FILE_EXTENSIONS.add(CompressionMethod.BZIP2.getExtension());
		KNOWN_FILE_EXTENSIONS.add(CompressionMethod.XZ.getExtension());

		if ((patterns == null || patterns.length == 0) && StringUtils.isBlank(sourceLocation)) {
			throw new IllegalArgumentException(
					"Either a source location, pattern, or both must be specified.");
		}

		// if an ExternalResourceLocator providing a custom ResourcePatternResolver
		// has been specified, use it, by default use PathMatchingResourcePatternresolver

		// If there are no patterns, then look for a pattern in the location itself.
		// If the source location contains a wildcard, split it up into a base and a pattern
		if (patterns == null) {
			int asterisk = sourceLocation.indexOf('*');
			int colon = sourceLocation.indexOf(':');
			if (asterisk != -1 && asterisk > colon) {
				// asterisk < colon in a case such as "classpath*:file.txt"
				int separator = Math.max(Math.max(
						sourceLocation.lastIndexOf(File.separatorChar, asterisk),
						sourceLocation.lastIndexOf('/', asterisk)), sourceLocation.lastIndexOf(':',
								asterisk));
				if (separator != -1) {
					// If there is a separator before the asterisk use it to separate into
					// base and pattern. This is meant to catch cases such as "dir/foo*.txt" of
					// file:foo*.txt
					patterns = new String[] {
							INCLUDE_PREFIX + sourceLocation.substring(separator + 1) };
					sourceLocation = sourceLocation.substring(0, separator + 1);
				}
				else {
					patterns = new String[] { INCLUDE_PREFIX + sourceLocation };
					sourceLocation = "";
				}
			}
		}

		// Parse the patterns and inject them into the FileSet
		List<String> includes = new ArrayList<String>();
		List<String> excludes = getDefaultExcludes();
		if (patterns != null) {
			for (String pattern : patterns) {
				if (pattern.startsWith(INCLUDE_PREFIX)) {
					includes.add(pattern.substring(INCLUDE_PREFIX.length()));
				}
				else if (pattern.startsWith(EXCLUDE_PREFIX)) {
					excludes.add(pattern.substring(EXCLUDE_PREFIX.length()));
				}
				else if (pattern.matches("^\\[.\\].*")) {
					throw new ResourceInitializationException(new IllegalArgumentException(
							"Patterns have to start with " + INCLUDE_PREFIX + " or "
									+ EXCLUDE_PREFIX + "."));
				}
				else {
					includes.add(pattern);
				}
			}
		}

		try {
			if (sourceLocation == null) {
				ListIterator<String> i = includes.listIterator();
				while (i.hasNext()) {
					i.set(locationToUrl(i.next()));
				}
				i = excludes.listIterator();
				while (i.hasNext()) {
					i.set(locationToUrl(i.next()));
				}
			}
			else {
				sourceLocation = locationToUrl(sourceLocation);
			}

			resources = new ArrayList<Resource>(scan(getSourceLocation(), includes, excludes));

			// Filter existing files
			if (targetLocation == null || targetLocation.isEmpty()) {
				System.out.println("Not checking for already existing files...");
			}
			else {
				Path outputDir = Paths.get(targetLocation);
				if(outputDir.toFile().exists()){
					System.out.println("Checking for already existing files in: " + outputDir);

					// Get existing files, map to filename, remove extensions
					try (Stream<Path> stream = Files.walk(outputDir)) {
						Set<String> existingFiles = stream
								.filter(Files::isRegularFile)
								.map(f -> outputDir.relativize(f).toString())
								.map(f -> removeFileExtensions(f))
								.collect(Collectors.toSet());
						System.out.println("Found " + existingFiles.size() + " existing files.");

						// Remove existing from collected resources
						int sizeBefore = resources.size();
						Path inputDir = Paths.get(sourceLocation);
						System.out.println("Checking " + sizeBefore + " files in input: " + inputDir);
						resources.removeIf(r -> existingFiles.contains(
								removeFileExtensions(
										inputDir.relativize(
												Paths.get(r.getLocation())
										).toString()
								)
						));
						int sizeAfter = resources.size();
						int sizeRemoved = sizeBefore - sizeAfter;
						System.out.println("Removed " + sizeRemoved + " files that already exist.");

						// If none removed, try checking parent directory
						if (sizeRemoved == 0 && checkParentOfSourceLocationForSkipFiles) {
							Path inputDirParent = inputDir.getParent();
							System.out.println("Checking again in input: " + inputDirParent);
							resources.removeIf(r -> existingFiles.contains(
									removeFileExtensions(
											inputDirParent.relativize(
													Paths.get(r.getLocation())
											).toString()
									)
							));
							sizeAfter = resources.size();
							sizeRemoved = sizeBefore - sizeAfter;
							System.out.println("Removed " + sizeRemoved + " files that already exist.");
						}
					}
				}
			}
			System.out.println("Processing " + resources.size() + " files...");

			if(sortBySize){
				System.out.println("Sorting by Size");
				Collections.sort(resources, new Comparator<Resource>() {

					@Override
					public int compare(Resource o1, Resource o2) {

						try {
							return (int) (Files.size(Paths.get(o1.getResolvedUri())) - Files.size(Paths.get(o2.getResolvedUri()))) ;
						} catch (IOException e) {
							return 0;
						}
					}
				});
			}

			progress = new ProgressMeter(resources.size());

			// Get the iterator that will be used to actually traverse the FileSet.
			resourceIterator = resources.iterator();

			getLogger().info("Found [" + resources.size() + "] resources to be read");
		}
		catch (IOException e) {
			throw new ResourceInitializationException(e);
		}
	}

	protected String removeFileExtensions(String filename) {
		boolean isDone = false;
		 while (!isDone) {
			isDone = true;
			for (String ext : KNOWN_FILE_EXTENSIONS ) {
				if (filename.endsWith(ext)) {
					// do not "replace" to make sure to remove only from end of filename
					filename = filename.substring(0, filename.length()-ext.length());
					isDone = false;
				}
			}
		};
		return filename;
	}

	protected List<String> getDefaultExcludes()
	{
		List<String> excludes = new ArrayList<String>();
		// These should be the same as documented here: http://ant.apache.org/manual/dirtasks.html
		if (useDefaultExcludes) {
			excludes.add("**/*~");
			excludes.add("**/#*#");
			excludes.add("**/.#*");
			excludes.add("**/%*%");
			excludes.add("**/._*");
			excludes.add("**/CVS");
			excludes.add("**/CVS/**");
			excludes.add("**/.cvsignore");
			excludes.add("**/SCCS");
			excludes.add("**/SCCS/**");
			excludes.add("**/vssver.scc");
			excludes.add("**/.svn");
			excludes.add("**/.svn/**");
			excludes.add("**/.DS_Store");
			excludes.add("**/.git");
			excludes.add("**/.git/**");
			excludes.add("**/.gitattributes");
			excludes.add("**/.gitignore");
			excludes.add("**/.gitmodules");
			excludes.add("**/.hg");
			excludes.add("**/.hg/**");
			excludes.add("**/.hgignore");
			excludes.add("**/.hgsub");
			excludes.add("**/.hgsubstate");
			excludes.add("**/.hgtags");
			excludes.add("**/.bzr");
			excludes.add("**/.bzr/**");
			excludes.add("**/.bzrignore");
		}
		return excludes;
	}

	/**
	 * Make sure the given location is an URL. E.g. adds "file:" if necessary.
	 *
	 * @param aLocation
	 *            the location.
	 * @return an URL.
	 * @throws MalformedURLException
	 *             if the location cannot be converted to a valid URL.
	 */
	protected String locationToUrl(String aLocation)
			throws MalformedURLException
	{
		String location = aLocation;

		if (isUnmarkedFileLocation(aLocation)) {
			location = new File(location).toURI().toURL().toString();
		}
		else if (location.startsWith(JAR_PREFIX) && !location.contains("!")) {
			// If we write something like "jar:file:/my/archive.zip", append the required "!"
			location += "!";
		}

		return location;
	}

	/**
	 * Checks if a location refers to a local file but does not start with "file:"
	 *
	 * @param aLocation
	 *            the location.
	 * @return if "file:" needs to be added to make the location explicit.
	 */
	private boolean isUnmarkedFileLocation(String aLocation)
	{
		// On Windows systems, an absolute path contains a colon at offset 1. If the offset is
		// 2 or greater, the colon likely is a scheme separator, not a drive letter separator.
		return aLocation.indexOf(':') < 2;
	}

	protected Collection<Resource> getResources()
	{
		return resources;
	}

	protected Iterator<Resource> getResourceIterator()
	{
		return resourceIterator;
	}

	protected Resource nextFile()
	{
		// reset for next document
		docModificationWritten = false;

		try {
			Resource res = resourceIterator.next();
			progress.setDone(completed);
			if (logFreq > 0 && completed % logFreq == 0) {
				getLogger().info(String.format("%s: %s", progress, res.location));
			}
			return res;
		}
		finally {
			completed++;
		}
	}

	protected String getSourceLocation()
	{
		return sourceLocation;
	}

	protected boolean isSingleLocation()
	{
		return patterns == null;
	}

	/**
	 * Get the base location used by the reader. This location always ends in a / if it is set at
	 * all. If there is no base, an empty string is returned.
	 *
	 * @return the base location used by the reader.
	 */
	protected String getBase()
	{
		return getBase(getSourceLocation());
	}

	protected String getBase(String aBase)
	{
		boolean singleLocation = patterns == null;

		String base;
		if (aBase != null) {
			base = aBase;
			// If this is a real base location, then add a "/" if there is none
			if (!singleLocation) {
				if (!base.endsWith("/") && !base.endsWith(":")) {
					base += "/";
				}
			}
		}
		else {
			base = "";
		}
		return base;
	}

	@Override
	public Progress[] getProgress()
	{
		return new Progress[] { new ProgressImpl(completed, resources.size(), "file") };
	}

	protected ResourcePatternResolver getResolver()
	{
		return resolver;
	}

	@Override
	public boolean hasNext()
			throws IOException, CollectionException
	{
		return resourceIterator.hasNext();
	}

	protected Collection<Resource> scan(String aBase, Collection<String> aIncludes,
			Collection<String> aExcludes)
					throws IOException
	{
		boolean singleLocation = isSingleLocation();
		String base = getBase(aBase);

		getLogger().info("Scanning [" + base + "]");

		Collection<String> includes;
		Collection<String> excludes;

		if (aIncludes == null || aIncludes.size() == 0) {
			if (!singleLocation) {
				includes = Collections.singleton("**/*");
			}
			else {
				includes = Collections.singleton("");
			}
		}
		else {
			includes = aIncludes;
		}

		if (aExcludes == null || aExcludes.size() == 0) {
			excludes = Collections.emptySet();
		}
		else {
			excludes = aExcludes;
		}

		AntPathMatcher matcher = new AntPathMatcher();
		List<Resource> result = new ArrayList<Resource>();

		// Collect the bases only if we need them later on. If no base is set, then getUri() will
		// not work because the base ("") may be resolved to one or more JAR locations and getUri()
		// internally expects file locations
		Set<String> rsBases = new HashSet<String>();
		if (base.length() > 0 && !singleLocation) {
			// E.g. a classpath location may resolve to multiple locations. Thus we collect all the
			// locations to which the base resolves.
			org.springframework.core.io.Resource[] rBases = resolver.getResources(base);
			for (org.springframework.core.io.Resource rBase : rBases) {
				URI uri = getUri(rBase, false);
				if (uri != null) {
					rsBases.add(uri.toString());
				}
			}
		}

		// Now we process the include patterns one after the other
		for (String include : includes) {
			// We resolve the resources for each base+include combination.
			org.springframework.core.io.Resource[] resourceList = resolver.getResources(base
					+ include);
			nextResource: for (org.springframework.core.io.Resource resource : resourceList) {
				URI uResource = getUri(resource, true);
				if (uResource == null) {
					continue;
				}
				String sResource = uResource.toString();

				// Determine the resolved base for this location
				String matchBase = null;
				if (base.length() > 0 && !singleLocation) {
					for (String b : rsBases) {
						if (!sResource.startsWith(b)) {
							continue;
						}

						// This is the base... at least we define it as being the base.
						// FIXME there may be other bases. Have to define a policy if most or least
						// specific base should be chosen.
						matchBase = b;
						break;
					}

					if (matchBase == null) {
						// This should not happen...
						throw new IllegalStateException("No base found for location [" + sResource
								+ "]");
					}
				}
				else {
					// If no base is set, no need to go through the trouble of finding one.
					matchBase = base;
				}

				// To figure out if the resolved location is excluded, we try to find the part
				// of the location that was determined by the include pattern by substracting the
				// resolved base locations one after the other and looking if the result is
				// matched by the exclude.
				if (excludes != null) {
					for (String exclude : excludes) {
						String rest = sResource.substring(matchBase.length());
						if (matcher.match(exclude, rest)) {
							if (getLogger().isDebugEnabled()) {
								getLogger().debug("Excluded: " + sResource);
							}
							continue nextResource;
						}
					}
				}

				// If the resource was not excluded, we add it to the results.
				String p = sResource.substring(matchBase.length());
				String loc = base + p;
				if (isSingleLocation()) {
					// If it was a single location, then use the parent folder as base
					p = StringUtils.substringAfterLast(matchBase, "/");
					matchBase = StringUtils.substringBeforeLast(matchBase, "/") + '/';
				}
				Resource r = new Resource(loc, base, resource.getURI(), matchBase, p, resource);
				result.add(r);
			}
		}

		Collections.sort(result, new Comparator<Resource>()
		{
			@Override
			public int compare(Resource aO1, Resource aO2)
			{
				return aO1.location.compareTo(aO2.location);
			}
		});

		if (singleLocation && result.isEmpty()) {
			throw new FileNotFoundException(
					"Resource not found or not a file: ["
							+ aBase
							+ "]. Please specify a file or use a pattern. Directories without patterns are "
							+ "not valid.");
		}

		return result;
	}

	/**
	 * Get the URI of the given resource.
	 *
	 * @param aResource
	 *            a resource
	 * @param aFileOrDir
	 *            if true try to return only files, if false try to return only dirs
	 * @return the URI of the resource
	 * @throws IOException
	 *             if an I/O error occurs.
	 */
	private URI getUri(org.springframework.core.io.Resource aResource, boolean aFileOrDir)
			throws IOException
	{
		try {
			final File file = aResource.getFile();

			// Exclude hidden files/dirs if requested
			if (file.isHidden() && !this.includeHidden) {
				return null;
			}

			// Return only dirs or files...
			if ((file.getPath().length() == 0) || (aFileOrDir && file.isFile())
					|| (!aFileOrDir && file.isDirectory())) {
				return aResource.getFile().toURI();
			}
			else {
				return null;
			}
		}
		catch (final IOException e) {
			return aResource.getURI();
		}
		catch (final UnsupportedOperationException e) {
			return aResource.getURI();
		}
	}

	/**
	 * Initialize the {@link DocumentMetaData}. This must be called before setting the document
	 * text, otherwise the end feature of this annotation will not be set correctly.
	 *
	 * @param aCas
	 *            the CAS.
	 * @param aResource
	 *            the resource from which the CAS is initialized.
	 */
	protected void initCas(CAS aCas, Resource aResource)
	{
		initCas(aCas, aResource, null);
	}

	/**
	 * Initialize the {@link DocumentMetaData}. This must be called before setting the document
	 * text, otherwise the end feature of this annotation will not be set correctly.
	 *
	 * @param aCas
	 *            the CAS.
	 * @param aResource
	 *            the resource from which the CAS is initialized.
	 * @param aQualifier
	 *            a qualifier if multiple CASes are generated from the same file.
	 */
	protected void initCas(CAS aCas, Resource aResource, String aQualifier)
	{
		String qualifier = aQualifier != null ? "#" + aQualifier : "";
		try {
			// Set the DKPro Core document metadata
			DocumentMetaData docMetaData = DocumentMetaData.create(aCas);
			docMetaData.setDocumentTitle(new File(aResource.getPath()).getName());
			docMetaData.setDocumentUri(aResource.getResolvedUri().toString() + qualifier);
			docMetaData.setDocumentId(aResource.getPath() + qualifier);
			if (aResource.getBase() != null) {
				docMetaData.setDocumentBaseUri(aResource.getResolvedBase());
				docMetaData.setCollectionId(aResource.getResolvedBase());
			}

			// Set the UIMA document metadata
			aCas.setDocumentLanguage(language);
		}
		catch (CASException e) {
			// This should not happen.
			throw new RuntimeException(e);
		}

		// set DocumentMeta on CAS init
		// handles "already set" internally
		setDocumentModification(aCas);
	}

	protected void setDocumentModification(CAS aCas) {
		// only set once
		if (docModificationWritten) {
			return;
		}

		// Set Document Modification Meta
		try {
			DocumentModification docModification = new DocumentModification(aCas.getJCas());
			docModification.setTimestamp(Instant.now().getEpochSecond());
			if (docModificationUser != null && !docModificationUser.isEmpty() ) {
				docModification.setUser(docModificationUser);
			}
			if (docModificationComment != null && !docModificationComment.isEmpty() ) {
				docModification.setComment(docModificationComment);
			}
			docModification.addToIndexes();
		}
		catch (Exception ex) {
			// ignore...
			ex.printStackTrace();
		}

		docModificationWritten = true;
	}

	public String getLanguage()
	{
		return language;
	}

	/**
	 */
	public static class Resource
	{
		private final String location;
		private final String base;
		private final URI resolvedUri;
		private final String resolvedBase;
		private final String path;
		private final org.springframework.core.io.Resource resource;

		public Resource(String aLocation, String aBase, URI aResolvedUri, String aResolvedBaseUri,
				String aPath, org.springframework.core.io.Resource aResource)
		{
			super();
			location = aLocation;
			base = aBase;
			resolvedUri = aResolvedUri;
			resolvedBase = aResolvedBaseUri;
			path = aPath;
			resource = aResource;
		}

		public String getLocation()
		{
			return location;
		}

		public String getBase()
		{
			return base;
		}

		public URI getResolvedUri()
		{
			return resolvedUri;
		}

		public String getResolvedBase()
		{
			return resolvedBase;
		}

		public String getPath()
		{
			return path;
		}

		public org.springframework.core.io.Resource getResource()
		{
			return resource;
		}

		public InputStream getInputStream()
				throws IOException
		{
			return resource.getInputStream();
		}

		@Override
		public int hashCode()
		{
			final int prime = 31;
			int result = 1;
			result = prime * result + ((resolvedUri == null) ? 0 : resolvedUri.hashCode());
			return result;
		}

		@Override
		public boolean equals(Object obj)
		{
			if (this == obj) {
				return true;
			}
			if (obj == null) {
				return false;
			}
			if (getClass() != obj.getClass()) {
				return false;
			}
			Resource other = (Resource) obj;
			if (resolvedUri == null) {
				if (other.resolvedUri != null) {
					return false;
				}
			}
			else if (!resolvedUri.equals(other.resolvedUri)) {
				return false;
			}
			return true;
		}

		@Override
		public String toString()
		{
			return location;
		}
	}
}
