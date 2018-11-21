package com.kgal.packagebuilder;

import java.io.File;
import java.io.IOException;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Properties;
import java.util.Vector;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.sforce.soap.metadata.FileProperties;
import com.sforce.soap.metadata.MetadataConnection;
//import com.sforce.soap.partner.PartnerConnection;
//import com.sforce.soap.partner.QueryResult;
//import com.sforce.soap.partner.sobject.SObject;
import com.sforce.soap.tooling.ToolingConnection;
import com.sforce.ws.ConnectionException;
import com.sforce.soap.metadata.*;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
//import org.apache.commons.cli.OptionGroup;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import com.salesforce.migrationtoolutils.Utils;

public class PackageBuilder {

	public enum Loglevel {
		VERBOSE (2), NORMAL (1), BRIEF (0);
		private final int level;

		Loglevel(int level) {
			this.level = level;
		}

		int getLevel() {return level;}

	};

	public enum OperationMode {
		DIR (0), ORG(1);

		private final int level;

		OperationMode(int level) {
			this.level = level;
		}

		int getLevel() {return level;}
	}

	String authEndPoint = "";

	private long timeStart;

	private MetadataConnection srcMetadataConnection;
	private ToolingConnection srcToolingConnection;
	private String srcUrl ;
	private String srcUser;
	private String srcPwd;

	private static final String urlBase = "/services/Soap/u/";
	private String targetDir = "";

	private static final int MAXITEMSINPACKAGE = 10000;
	private static final double API_VERSION = 44.0;
	private static double myApiVersion;
	private static String skipItems;
	private static ArrayList<Pattern> skipPatterns = new ArrayList<Pattern>();
	private static HashMap<String, DescribeMetadataObject> describeMetadataObjectsMap;

	private static final boolean FILTERVERSIONLESSFLOWS = true;

	private static HashSet<String> existingTypes = new HashSet<String>();
	private static HashMap<String,String> parameters = new HashMap<String,String>();

	private static CommandLine line = null;
	private static Options options = new Options();

	private Loglevel loglevel;
	private OperationMode mode;
	//	private boolean isLoggingPartialLine = false;



	public static void main(String[] args) throws RemoteException, Exception {

		PackageBuilder sample = new PackageBuilder();
		setupOptions();
		sample.parseCommandLine(args);




		//		if (args.length < 2) {
		//			System.out.println("Usage: java -jar PackageBuilder.jar <org property file path> <fetch property path> [metadataitems=item1,item2,...] [skipitems=skipPattern1,skipPatter2,...]");
		//			System.out.println("properties\test.properties properties\fetch.properties - will list the items defined in the fetch.properties file from the org specified in the file properties\test.properties and put them in the target directory specified in the properties\fetch.properties file ");
		//			System.out.println("providing metadataitems and/or skipitems parameters on command line will override the same from the fetch properties file");
		//			System.out.println("Parameters not supplied - exiting.");
		//			System.exit(0);
		//		}
		//		
		//		if (args.length > 0) {
		//			sample.sourceProps = Utils.initProps(args[0]);
		//		}
		//		
		//		if (args.length > 1) {
		//			sample.fetchProps = Utils.initProps(args[1]);
		//		}
		//		


		sample.run();
	}

	public void run() throws RemoteException, Exception {

		// set loglevel based on parameters

		if (parameters.get("loglevel") != null && parameters.get("loglevel").equals("verbose")) {
			loglevel = Loglevel.NORMAL;
		} else {
			loglevel = Loglevel.BRIEF;
		}

		// initialize inventory - it will be used in both types of operations
		// (connect to org or run local)

		HashMap<String,ArrayList<String>> inventory = new HashMap<String,ArrayList<String>>(); 
		myApiVersion = Double.parseDouble(parameters.get("apiversion"));
		this.targetDir = Utils.checkPathSlash(Utils.checkPathSlash(parameters.get("targetdirectory")));

		// handling for building a package from a directory
		// if we have a base directory set, ignore everything else and generate from the directory

		if (parameters.get("basedirectory") != null) {
			generateInventoryFromDir(inventory);
			mode = OperationMode.DIR;
		} else {
			generateInventoryFromOrg(inventory);
			mode = OperationMode.ORG;
		}
		generatePackageXML(inventory);
	}

	private void generateInventoryFromOrg(HashMap<String, ArrayList<String>> inventory) throws RemoteException, Exception {

		//		Initialize the metadata connection we're going to need

		srcUrl = parameters.get("serverurl") + urlBase + myApiVersion;
		srcUser = parameters.get("username");
		srcPwd = parameters.get("password");
		skipItems = parameters.get("skipItems");
		// Make a login call to source
		this.srcMetadataConnection = LoginUtil.mdLogin(srcUrl, srcUser, srcPwd);

		//		Figure out what we are going to be fetching

		ArrayList<String> workToDo = new ArrayList<String>(getTypesToFetch());
		Collections.sort(workToDo);

		log("Will fetch: " + String.join(", ", workToDo) + " from: " + srcUrl, Loglevel.BRIEF);
		log("Using user: " + srcUser + " skipping: " + skipItems, Loglevel.NORMAL);

		System.out.println("target directory: " + this.targetDir);

		Utils.checkDir(targetDir);

		Iterator<String> i = workToDo.iterator();
		int counter = 0;
		while (i.hasNext()) {
			counter ++;
			String mdType = i.next();
			if (loglevel.getLevel() > Loglevel.BRIEF.getLevel()) {
				log("*********************************************", Loglevel.NORMAL);
				log("Processing type " + counter + " out of " + workToDo.size() + ": " + mdType, Loglevel.NORMAL);
				log("*********************************************", Loglevel.NORMAL);
			} else if (loglevel == Loglevel.BRIEF) {
				logPartialLine("Processing type " + counter + " out of " + workToDo.size() + ": " + mdType, Loglevel.BRIEF);
			}


			ArrayList<String> mdTypeItemList = fetchMetadataType(mdType);
			Collections.sort(mdTypeItemList);
			inventory.put(mdType, mdTypeItemList);

			if (loglevel.getLevel() > Loglevel.BRIEF.getLevel()) {
				log("---------------------------------------------", Loglevel.NORMAL);
				log("Finished processing: " + mdType, Loglevel.NORMAL);
				log("---------------------------------------------", Loglevel.NORMAL);
			} else if (loglevel == Loglevel.BRIEF) {
				log(" items: " + mdTypeItemList.size(), Loglevel.BRIEF);
			}
		}

	}

	// added method for generating an inventory based on a local directory
	// rather than an org

	private HashSet<String> getTypesToFetch() throws ConnectionException {

		HashSet<String> typesToFetch = new HashSet<String>();
		String mdTypesToExamine = parameters.get("metadataitems");

		//		if a metadataitems parameter was provided, we use that

		if (mdTypesToExamine != null) {
			for (String s : mdTypesToExamine.split(",")) {
				typesToFetch.add(s.trim());
			}
		} else {
			//			no directions on what to fetch - go get everything
			log("No metadataitems (-mi) parameter found, will inventory the whole org", Loglevel.BRIEF);

			// get a describe

			DescribeMetadataResult dmr = this.srcMetadataConnection.describeMetadata(myApiVersion);
			describeMetadataObjectsMap = new HashMap<String, DescribeMetadataObject>();

			for (DescribeMetadataObject obj : dmr.getMetadataObjects()) {
				describeMetadataObjectsMap.put(obj.getXmlName(), obj);
			}

			for (String obj : describeMetadataObjectsMap.keySet()) {
				typesToFetch.add(obj.trim());
			}
		}
		return typesToFetch;
	}

	private void generateInventoryFromDir(HashMap<String, ArrayList<String>> inventory) throws IOException {
		String basedir = parameters.get("basedirectory");

		// check if the directory is valid

		HashMap<String, HashSet<String>> myInventory = new HashMap<String, HashSet<String>>();

		if (!Utils.checkIsDirectory(basedir)) {
			// log error and exit

			log("Base directory parameter provided: " + basedir + " invalid or is not a directory, cannot continue.", Loglevel.BRIEF);
			System.exit(1);	
		}

		// directory valid - enumerate and generate inventory

		Vector<String> filelist = generateFileList(new File(basedir), basedir);

		// so now we have a list of folders/files 
		// need to convert to inventory for package.xml generator

		for(String s : filelist) {
			// ignore -meta.xml


			if (s.contains("-meta.xml")) continue;

			// split into main folder + rest

			try {

				// ignore anything which doesn't have a path separator (i.e. not a folder)

				int separatorLocation = s.indexOf(File.separator);

				if (separatorLocation == -1) {
					log("No folder in: " + s + ",skipping...", Loglevel.VERBOSE);
					continue;
				}

				String foldername = s.substring(0,separatorLocation);
				String filename = s.substring(separatorLocation+1);

				// split off file name suffix

				filename = filename.substring(0, filename.lastIndexOf("."));

				// ignore anything starting with a .

				if (filename.startsWith(".")) continue;

				// figure out based on foldername what the metadatatype is

				String mdType = Utils.getMetadataTypeForDir(foldername);

				// if not found, try lowercase
				if (mdType == null) {
					mdType = Utils.getMetadataTypeForDir(foldername.toLowerCase());
				}

				if (mdType == null) {
					log("Couldn't find type mapping for item : " + mdType + " : " + filename + ", original path: " + s + ",skipping...", Loglevel.BRIEF);
					continue;
				}

				// generate inventory entry

				HashSet<String> typeInventory = myInventory.get(mdType);
				if (typeInventory == null) {
					typeInventory = new HashSet<String>();
					myInventory.put(mdType, typeInventory);
					System.out.println("Created inventory record for type: " + mdType);
				}

				// check if there is a folder in the filename and it's aura - then we need to leave the folder, skip the item

				if (filename.contains("/") && mdType.equals("AuraDefinitionBundle")) {
					String subFoldername = filename.substring(0,filename.indexOf("/"));
					typeInventory.add(subFoldername);
					log("Added: " + mdType + " : " + subFoldername + ", to inventory, original path: " + s, Loglevel.NORMAL);
					continue;
				}

				// check if there is a folder in the filename - then we need to add the folder as well

				if (filename.contains("/")) {
					String subFoldername = filename.substring(0,filename.indexOf("/"));
					typeInventory.add(subFoldername);
				}

				typeInventory.add(filename);
				log("Added: " + mdType + " : " + filename + ", to inventory, original path: " + s, Loglevel.NORMAL);

				// convert myinventory to the right return type


			} catch (Exception e) {
				//				Something bad happened
				System.out.println("Something bad happened on file: " + s + ", skipping...");
			}


		}
		for (String myMdType : myInventory.keySet()) {
			ArrayList<String> invType = new ArrayList<String>();
			invType.addAll(myInventory.get(myMdType));
			inventory.put(myMdType, invType);
		}



		//

	}

	private static Vector<String> generateFileList(File node, String baseDir) {

		Vector<String> retval = new Vector<String>();
		// add file only
		if (node.isFile()) {
			retval.add(generateZipEntry(node.getAbsoluteFile().toString(), baseDir));
			//			retval.add(baseDir + "/" + node.getAbsoluteFile().toString());
			//			retval.add(node.getName()); 
		} else if (node.isDirectory()) {
			String[] subNote = node.list();
			for (String filename : subNote) {
				retval.addAll(generateFileList(new File(node, filename), baseDir));
			}
		}
		return retval;
	}

	private static String generateZipEntry(String file, String sourceFolder) {
		int indexOfSourceFolder = file.lastIndexOf(sourceFolder);
		return file.substring(indexOfSourceFolder + sourceFolder.length() + 1, file.length()); 
	}

	// inventory is a list of lists
	// keys are the metadata types
	// e.g. flow, customobject, etc.

	/*	private void writePackageXmlFile(HashMap<String, ArrayList<String>> theMap, String filename)
	 * 
	 * this method will generate a package.xml file based on a HashMap<String, ArrayList<String>> and a filename 
	used to cater for big orgs that burst limits
	input hashmap structure:
	ApexClass -> [Class1,Class2]
	ApexTrigger -> [Trigger1,Trigger2]

	expecting only to see types which are populated (i.e. have at least 1 item)

	Expected output:
	<types>
		<name>ApexClass</name>
		<members>Class1</members>
		<members>Class2</members>
	</types>
	<types>
		<name>ApexTrigger</name>
		<members>Trigger1</members>
		<members>Trigger2</members>
	</types>

	 */	
	private void writePackageXmlFile(HashMap<String, ArrayList<String>> theMap, String filename) throws IOException {
		StringBuffer packageXML = new StringBuffer();
		packageXML.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
		packageXML.append("<Package xmlns=\"http://soap.sforce.com/2006/04/metadata\">\n");


		ArrayList<String> mdTypes = new ArrayList<String>(theMap.keySet());
		Collections.sort(mdTypes);


		for (String mdType : mdTypes) {
			packageXML.append("\t<types>\n");
			packageXML.append("\t\t<name>" + mdType + "</name>\n");

			for (String mdName : theMap.get(mdType)) {
				packageXML.append("\t\t<members>" + mdName + "</members>\n");	
			}
			packageXML.append("\t</types>\n");
		}
		packageXML.append("\t<version>" + myApiVersion + "</version>\n");
		packageXML.append("</Package>\n");

		Utils.writeFile(targetDir + filename, packageXML.toString());
		log("Writing " + new File (targetDir + filename).getCanonicalPath(), Loglevel.BRIEF);
	}



	private void generatePackageXML(HashMap<String, ArrayList<String>> inventory) throws ConnectionException, IOException {

		int itemCount = 0;
		int skipCount = 0;

		HashMap<String, ArrayList<String>> myFile = new HashMap<String, ArrayList<String>>();

		ArrayList<String> types = new ArrayList<String>();
		types.addAll(inventory.keySet());
		Collections.sort(types);

		for (String mdType : types) {

			//			check if we have any items in this category

			ArrayList<String> items = inventory.get(mdType);
			if (items.size() < 1) {
				continue;
			}

			myFile.put(mdType, new ArrayList<String>());

			Collections.sort(items);
			for (String item : items) {



				// special treatment for flows
				// get rid of items returned without a version number
				//		<members>Update_Campaign_path_on_oppty</members>  ****  FILTER THIS ONE OUT SO IT DOESN'T APPEAR***
				//		<members>Update_Campaign_path_on_oppty-4</members>
				//		<members>Update_Campaign_path_on_oppty-5</members>

				if (mdType.toLowerCase().equals("flow") && FILTERVERSIONLESSFLOWS) {
					if (!item.contains("-")) {
						// we won't count this one as skipped, since it shouldn't be there in the first place
						continue;
					}
				}
				myFile.get(mdType).add(item);
				itemCount++;
			}

			// special treatment for flows
			// make a callout to Tooling API to get latest version for Active flows (which the shi+ Metadata API won't give you)

			// only do this if we're running in org mode

			if (mdType.toLowerCase().equals("flow") && mode == OperationMode.ORG) {


				String flowQuery = 	"SELECT DeveloperName ,ActiveVersion.VersionNumber " +
						"FROM FlowDefinition " +
						"WHERE ActiveVersion.VersionNumber <> NULL";

				this.srcToolingConnection = LoginUtil.toolingLogin(srcUrl, srcUser, srcPwd);
				com.sforce.soap.tooling.QueryResult qr = srcToolingConnection.query(flowQuery);
				com.sforce.soap.tooling.sobject.SObject[] records = qr.getRecords();
				for (com.sforce.soap.tooling.sobject.SObject record : records) {
					com.sforce.soap.tooling.sobject.FlowDefinition fd = (com.sforce.soap.tooling.sobject.FlowDefinition) record;
					myFile.get(mdType).add(fd.getDeveloperName() + "-" + fd.getActiveVersion().getVersionNumber());
					itemCount++;
				}
			}
		}

		//		now check if anything we have needs to be skipped

		skipCount = handleSkippingItems(myFile);

		//		now break it up into files if needed

		HashMap<String, ArrayList<String>>[] files = breakPackageIntoFiles(myFile);

		for (int i = 0; i < files.length; i++) {
			if (i == 0) {
				writePackageXmlFile(files[i], "package.xml");
			} else {
				writePackageXmlFile(files[i], "package." + i + ".xml");
			}
		}



		ArrayList<String> typesFound = new ArrayList<String>(existingTypes);
		Collections.sort(typesFound);

		log("Types found in org: " + typesFound.toString(), Loglevel.BRIEF);

		log("Total items in package.xml: " + itemCount, Loglevel.BRIEF);
		log("Total items skipped: " + skipCount + " (excludes count of items in type where entire type was skipped)", Loglevel.NORMAL);
	}

	private HashMap<String, ArrayList<String>>[] breakPackageIntoFiles(HashMap<String, ArrayList<String>> myFile) {

		ArrayList<HashMap<String, ArrayList<String>>> files = new ArrayList<HashMap<String, ArrayList<String>>>();
		int fileIndex = 0;
		int fileCount = 0;
		HashMap<String, ArrayList<String>> currentFile = new HashMap<String, ArrayList<String>>();
		for (String mdType : myFile.keySet()) {
			ArrayList<String> mdTypeList = myFile.get(mdType);
			int mdTypeSize = mdTypeList.size();
			
			
//			do we have room in this file for the 
			if (fileCount + mdTypeSize > MAXITEMSINPACKAGE) {
//				no, we don't, finish file off, add to list, create new and add to that
				files.add(currentFile);
				currentFile = new HashMap<String, ArrayList<String>>();
				
				log("Finished composing file " + fileIndex + ", total count: " + fileCount + "items.", Loglevel.NORMAL);
				fileCount = 0;
				fileIndex++;
			}
//			now add this type to this file and continue
			currentFile.put(mdType, mdTypeList);
			fileCount += mdTypeSize;
			log("Adding type: " + mdType + "(" + mdTypeSize + " items) to file " + fileIndex + ", total count now: " + fileCount, Loglevel.NORMAL);
		}
		
//		finish off any last file
		files.add(currentFile);		
		log("Finished composing file " + fileIndex + ", total count: " + fileCount + "items.", Loglevel.NORMAL);
		
		
		@SuppressWarnings("unchecked")
		HashMap<String, ArrayList<String>>[] retval = (HashMap<String, ArrayList<String>>[]) new HashMap[files.size()];
		
		retval = files.toArray(retval);

		return retval;
	}

	private int handleSkippingItems(HashMap<String, ArrayList<String>> myFile) {

		int skipCount = 0;

		//		Initiate patterns array
		//		TODO: handle non-existent parameter in the config files

		if (skipItems != null) {
			for (String p : skipItems.split(",")) {
				try {
					skipPatterns.add(Pattern.compile(p));
				} catch (PatternSyntaxException  e) {
					System.out.println("Tried to compile pattern: " + p + " but got exception: ");
					e.printStackTrace();
				}
			}
		}

		for (String mdType : myFile.keySet()) {
			//			first, check if any of the patterns match the whole type
			String mdTypeFullName = mdType + ":";
			for (Pattern p : skipPatterns) {

				Matcher m = p.matcher(mdTypeFullName);
				if (m.matches()) {
					log("Skip pattern: " + p.pattern() + " matches the metadata type: " + mdTypeFullName + ", entire type will be skipped.", Loglevel.NORMAL);

					// remove the whole key from the file

					skipCount += myFile.get(mdType).size();

					myFile.remove(mdType);
					continue;

				}
			}

			ArrayList<String> items = myFile.get(mdType);
			Collections.sort(items);
			for (int i = 0; i < items.size(); i++) {
				mdTypeFullName = mdType + ":" + items.get(i);
				for (Pattern p : skipPatterns) {
					Matcher m = p.matcher(mdTypeFullName);
					if (m.matches()) {
						log("Skip pattern: " + p.pattern() + " matches the metadata item: " + mdTypeFullName + ", item will be skipped.", Loglevel.NORMAL);
						items.remove(i);
						skipCount++;
					}
				}
			}
		}
		return skipCount;
	}

	private ArrayList<String> fetchMetadataType (String metadataType) throws RemoteException, Exception {
		startTiming();
		//logPartialLine(", level);
		ArrayList<String> packageMap = new ArrayList<String>();
		try {

			ArrayList<FileProperties> foldersToProcess = new ArrayList<FileProperties>();
			boolean isFolder = false;
			// check if what we have here is in folders

			DescribeMetadataObject obj = describeMetadataObjectsMap.get(metadataType);
			if (obj != null && obj.getInFolder() == true) {
				isFolder = true;
				log(metadataType + " is stored in folders. Getting folder list.", Loglevel.VERBOSE);
				ListMetadataQuery query = new ListMetadataQuery();
				// stupid hack for emailtemplate folder name
				String type;
				if (metadataType.toLowerCase().equals("emailtemplate")) {
					type = "EmailFolder";
				} else {
					type = metadataType + "Folder";
				}

				query.setType(type);
				FileProperties[] srcMd = srcMetadataConnection.listMetadata(new ListMetadataQuery[] { query }, myApiVersion);
				if (srcMd != null && srcMd.length > 0) {
					for (FileProperties n : srcMd) {
						foldersToProcess.add(n);
					}
				}
			}

			Iterator<FileProperties> folder = foldersToProcess.iterator();

			HashMap<String, ArrayList<FileProperties>> metadataMap = new HashMap<String, ArrayList<FileProperties>>();

			int itemCount = 0;
			//			int thisItemCount = 0;


			do {
				FileProperties folderProperties = null;
				ListMetadataQuery query = new ListMetadataQuery();


				query.setType(metadataType);
				String folderName = null;
				if (isFolder && folder.hasNext()) {
					folderProperties = folder.next(); 
					folderName = folderProperties.getFullName();
					query.setFolder(folderName);
				}

				// Assuming that the SOAP binding has already been established.

				// generate full metadata inventory


				FileProperties[] srcMd = srcMetadataConnection.listMetadata(new ListMetadataQuery[] { query }, myApiVersion);
				itemCount += srcMd.length;
				//				thisItemCount = srcMd.length;
				if (folderName != null) {
					log("Processing folder: " + folderName + " " + " items: " + srcMd.length + "\tCurrent total: " + itemCount, Loglevel.NORMAL);
					// fetch folders themselves
					packageMap.add(folderName);
					ArrayList<FileProperties> filenameList = new ArrayList<FileProperties>();
					filenameList.add(folderProperties);
					metadataMap.put(folderProperties.getFileName(), filenameList);
					itemCount++;
				}

				if (itemCount > 0) {
					existingTypes.add(metadataType);
				}

				if (srcMd != null && srcMd.length > 0 
						|| metadataType.equals("StandardValueSet")) { // hack alert - currently listMetadata call will return nothing for StandardValueSet
					if (!metadataType.equals("StandardValueSet")) {
						for (FileProperties n : srcMd) {
							if (n.getNamespacePrefix() == null || n.getNamespacePrefix().equals("")) {
								packageMap.add(n.getFullName());	
							}

						}
					} else {
						for (String s : STANDARDVALUETYPESARRAY) packageMap.add(s);
					}


				} else {
					if (!isFolder) {
						log("No items of this type, skipping...", Loglevel.VERBOSE);
						break;
					}
					if (!folder.hasNext()) {
						break;
					}
				}
				if (isFolder == true && folder.hasNext()) {
					continue;
				}

			} while (folder.hasNext());

		} catch (ConnectionException ce) {
			//			ce.printStackTrace();
			System.out.println("Exception processing: " + metadataType);
			System.out.println(ce.getMessage());
		}

		endTiming();
		return packageMap;
	}

	private void startTiming() {
		timeStart = System.currentTimeMillis();
	}

	private void endTiming() {
		long end = System.currentTimeMillis();
		long diff = ((end - timeStart));
		String hms = String.format("%02d:%02d:%02d", TimeUnit.MILLISECONDS.toHours(diff),
				TimeUnit.MILLISECONDS.toMinutes(diff) - TimeUnit.HOURS.toMinutes(TimeUnit.MILLISECONDS.toHours(diff)),
				TimeUnit.MILLISECONDS.toSeconds(diff) - TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(diff)));
		log("Duration: " + hms, Loglevel.NORMAL);
	}

	private static void setupOptions() {

		options.addOption( Option.builder("o").longOpt( "orgfile" )
				.desc( "file containing org parameters (see below)" )
				.hasArg()
				.build() );
		options.addOption( Option.builder("u").longOpt( "username" )
				.desc( "username for the org (someuser@someorg.com)" )
				.hasArg()
				.build() );
		options.addOption( Option.builder("p").longOpt( "password" )
				.desc( "password for the org (t0pSecr3t)" )
				.hasArg()
				.build() );
		options.addOption( Option.builder("s").longOpt( "serverurl" )
				.desc( "server URL for the org (https://login.salesforce.com)" )
				.hasArg()
				.build() );
		options.addOption( Option.builder("a").longOpt( "apiversion" )
				.desc( "api version to use, will default to " + API_VERSION)
				.hasArg()
				.build() );
		options.addOption( Option.builder("mi").longOpt( "metadataitems" )
				.desc( "metadata items to fetch" )
				.hasArg()
				.build() );
		options.addOption( Option.builder("sp").longOpt( "skippatterns" )
				.desc( "patterns to skip when fetching" )
				.hasArg()
				.build() );
		options.addOption( Option.builder("d").longOpt( "destination" )
				.desc( "directory where the generated package.xml will be written" )
				.hasArg()
				.build() );

		// handling for building a package from a directory

		options.addOption( Option.builder("b").longOpt( "basedirectory" )
				.desc( "base directory from which to generate package.xml" )
				.hasArg()
				.build() );		

		// adding handling for brief output parameter

		options.addOption( Option.builder("v").longOpt( "verbose" )
				.desc( "output verbose logging instead of just core output" )
				.build() );
	}

	private static void printHelp() {
		HelpFormatter formatter = new HelpFormatter();
		formatter.setOptionComparator(null);

		formatter.printHelp( "java -jar PackageBuilder.jar [-b basedirectory] [-o <parameter file1>,<parameter file2>] [-u <SF username>] [-p <SF password>]", options );
	}

	private void parseCommandLine(String[] args) {

		parameters.put("apiversion", "" + API_VERSION);
		parameters.put("metadataitems", null);
		parameters.put("skipItems", null);
		parameters.put("serverurl", null);
		parameters.put("username", null);
		parameters.put("password", null);
		parameters.put("targetdirectory", null);

		// adding handling for building a package from a directory

		parameters.put("basedirectory", null);

		HashSet<String> nonMandatoryParams = new HashSet<String>();
		nonMandatoryParams.add("skipItems");

		CommandLineParser parser = new DefaultParser();
		try {
			// parse the command line arguments
			line = parser.parse( options, args );
		}
		catch( ParseException exp ) {
			// oops, something went wrong
			System.err.println( "Command line parsing failed.  Reason: " + exp.getMessage() );
			System.exit(-1);
		}

		if (line != null) {
			// first initialize parameters from any parameter files provided

			if (line.hasOption("o") && line.getOptionValue("o") != null && line.getOptionValue("o").length() > 0) {
				String paramFilesParameter = line.getOptionValue("o");
				for (String paramFileName : paramFilesParameter.split(",")) {
					Properties props = Utils.initProps(paramFileName.trim());
					System.out.println("Loading parameters from file: " + paramFileName);
					parameters.put("apiversion", props.getProperty("sf.apiversion") == null ? parameters.get("apiversion") : "" + Double.parseDouble(props.getProperty("sf.apiversion")));
					parameters.put("metadataitems", props.getProperty("metadataitems") == null ? parameters.get("metadataitems") : props.getProperty("metadataitems"));
					parameters.put("serverurl", props.getProperty("sf.serverurl") == null ? parameters.get("serverurl") : props.getProperty("sf.serverurl"));
					parameters.put("username", props.getProperty("sf.username") == null ? parameters.get("username") : props.getProperty("sf.username"));
					parameters.put("password", props.getProperty("sf.password") == null ? parameters.get("password") : props.getProperty("sf.password"));
					parameters.put("skipItems", props.getProperty("skipItems") == null ? parameters.get("skipItems") : props.getProperty("skipItems"));
					parameters.put("basedirectory", props.getProperty("basedirectory") == null ? parameters.get("basedirectory") : props.getProperty("basedirectory"));

					// adding handling for building a package from a directory

					parameters.put("targetdirectory", props.getProperty("targetdirectory") == null ? parameters.get("targetdirectory") : props.getProperty("targetdirectory"));
				}
			}

			// now add all parameters form the commandline
			if (line.hasOption("a") && line.getOptionValue("a") != null && line.getOptionValue("a").length() > 0) {
				parameters.put("apiversion", line.getOptionValue("a"));
			}
			if (line.hasOption("u") && line.getOptionValue("u") != null && line.getOptionValue("u").length() > 0) {
				parameters.put("username", line.getOptionValue("u"));
			}
			if (line.hasOption("s") && line.getOptionValue("s") != null && line.getOptionValue("s").length() > 0) {
				parameters.put("serverurl", line.getOptionValue("s"));
			}
			if (line.hasOption("p") && line.getOptionValue("p") != null && line.getOptionValue("p").length() > 0) {
				parameters.put("password", line.getOptionValue("p"));
			}
			if (line.hasOption("mi") && line.getOptionValue("mi") != null && line.getOptionValue("mi").length() > 0) {
				parameters.put("metadataitems", line.getOptionValue("mi"));
			}
			if (line.hasOption("sp") && line.getOptionValue("sp") != null && line.getOptionValue("sp").length() > 0) {
				parameters.put("skipItems", line.getOptionValue("sp"));
			}
			if (line.hasOption("d") && line.getOptionValue("d") != null && line.getOptionValue("d").length() > 0) {
				parameters.put("targetdirectory", line.getOptionValue("d"));
			}

			// adding handling for building a package from a directory

			if (line.hasOption("b") && line.getOptionValue("b") != null && line.getOptionValue("b").length() > 0) {
				parameters.put("basedirectory", line.getOptionValue("b"));
			}

			// adding handling for brief output parameter

			if (line.hasOption("v")) {
				parameters.put("loglevel", "verbose");
			}

			//			add default to current directory if no target directory given

			if (!isParameterProvided("targetdirectory")) {
				log("No target directory provided, will default to current directory.", Loglevel.BRIEF);
				parameters.put("targetdirectory",".");				
			}


			// check that we have the minimum parameters
			// either b(asedir) and d(estinationdir)
			// or s(f_url), p(assword), u(sername), mi(metadataitems)
			boolean canProceed = false;

			if (isParameterProvided("basedirectory") &&
					isParameterProvided("targetdirectory")) {
				canProceed = true;
			} else {
				if (isParameterProvided("serverurl") &&
						isParameterProvided("password") &&
						isParameterProvided("password") 
						//						
						//						no longer required since we can inventory the org
						//						&& isParameterProvided("metadataitems")	
						//						
						) {
					canProceed = true; 
				} else {
					System.out.println("Mandatory parameters not provided in files or commandline -"
							+ " either basedir and destination or serverurl, username, password and metadataitems required as minimum");
					System.out.println("Visible parameters:");
					for (String key : parameters.keySet()) {
						System.out.println(key + ":" + parameters.get(key));
					}
				}
			}

			for (String key : parameters.keySet()) {
				System.out.println(key + ":" + parameters.get(key));
			}

			if (!canProceed) {
				printHelp();
				System.exit(1);
			}
		} else printHelp();
	}

	private static boolean isParameterProvided(String parameterName) {
		if (parameters.get(parameterName) != null && parameters.get(parameterName).length() > 0) {
			return true;
		}
		return false;
	}

	private void log (String logText, Loglevel level) {
		if (loglevel == null || level.getLevel() <= loglevel.getLevel()) {
			System.out.println (logText);
		}
	}

	private void logPartialLine (String logText, Loglevel level) {
		if (level.getLevel() <= loglevel.getLevel()) {
			System.out.print(logText);
		}
	}

	private static final String[] STANDARDVALUETYPESARRAY = new String[]
			{"AccountContactMultiRoles","AccountContactRole","AccountOwnership","AccountRating","AccountType","AddressCountryCode","AddressStateCode",
					"AssetStatus","CampaignMemberStatus","CampaignStatus","CampaignType","CaseContactRole","CaseOrigin","CasePriority","CaseReason",
					"CaseStatus","CaseType","ContactRole","ContractContactRole","ContractStatus","EntitlementType","EventSubject","EventType",
					"FiscalYearPeriodName","FiscalYearPeriodPrefix","FiscalYearQuarterName","FiscalYearQuarterPrefix","IdeaCategory1",
					"IdeaMultiCategory","IdeaStatus","IdeaThemeStatus","Industry","InvoiceStatus","LeadSource","LeadStatus","OpportunityCompetitor",
					"OpportunityStage","OpportunityType","OrderStatus1","OrderType","PartnerRole","Product2Family","QuestionOrigin1","QuickTextCategory",
					"QuickTextChannel","QuoteStatus","SalesTeamRole","Salutation","ServiceContractApprovalStatus","SocialPostClassification",
					"SocialPostEngagementLevel","SocialPostReviewedStatus","SolutionStatus","TaskPriority","TaskStatus","TaskSubject","TaskType",
					"WorkOrderLineItemStatus","WorkOrderPriority","WorkOrderStatus"};

	/*private static final String STANDARDVALUETYPES = "<types>\n"
			+ "<members>AccountContactMultiRoles</members>\n"
			+ "<members>AccountContactRole</members>\n"
			+ "<members>AccountOwnership</members>\n"
			+ "<members>AccountRating</members>\n"
			+ "<members>AccountType</members>\n"
			+ "<members>AddressCountryCode</members>\n"
			+ "<members>AddressStateCode</members>\n"
			+ "<members>AssetStatus</members>\n"
			+ "<members>CampaignMemberStatus</members>\n"
			+ "<members>CampaignStatus</members>\n"
			+ "<members>CampaignType</members>\n"
			+ "<members>CaseContactRole</members>\n"
			+ "<members>CaseOrigin</members>\n"
			+ "<members>CasePriority</members>\n"
			+ "<members>CaseReason</members>\n"
			+ "<members>CaseStatus</members>\n"
			+ "<members>CaseType</members>\n"
			+ "<members>ContactRole</members>\n"
			+ "<members>ContractContactRole</members>\n"
			+ "<members>ContractStatus</members>\n"
			+ "<members>EntitlementType</members>\n"
			+ "<members>EventSubject</members>\n"
			+ "<members>EventType</members>\n"
			+ "<members>FiscalYearPeriodName</members>\n"
			+ "<members>FiscalYearPeriodPrefix</members>\n"
			+ "<members>FiscalYearQuarterName</members>\n"
			+ "<members>FiscalYearQuarterPrefix</members>\n"
			+ "<members>IdeaCategory1</members>\n"
			+ "<members>IdeaMultiCategory</members>\n"
			+ "<members>IdeaStatus</members>\n"
			+ "<members>IdeaThemeStatus</members>\n"
			+ "<members>Industry</members>\n"
			+ "<members>InvoiceStatus</members>\n"
			+ "<members>LeadSource</members>\n"
			+ "<members>LeadStatus</members>\n"
			+ "<members>OpportunityCompetitor</members>\n"
			+ "<members>OpportunityStage</members>\n"
			+ "<members>OpportunityType</members>\n"
			+ "<members>OrderStatus</members>\n"
			+ "<members>OrderType</members>\n"
			+ "<members>PartnerRole</members>\n"
			+ "<members>Product2Family</members>\n"
			+ "<members>QuestionOrigin</members>\n"
			+ "<members>QuickTextCategory</members>\n"
			+ "<members>QuickTextChannel</members>\n"
			+ "<members>QuoteStatus</members>\n"
			+ "<members>SalesTeamRole</members>\n"
			+ "<members>Salutation</members>\n"
			+ "<members>ServiceContractApprovalStatus</members>\n"
			+ "<members>SocialPostClassification</members>\n"
			+ "<members>SocialPostEngagementLevel</members>\n"
			+ "<members>SocialPostReviewedStatus</members>\n"
			+ "<members>SolutionStatus</members>\n"
			+ "<members>TaskPriority</members>\n"
			+ "<members>TaskStatus</members>\n"
			+ "<members>TaskSubject</members>\n"
			+ "<members>TaskType</members>\n"
			+ "<members>WorkOrderLineItemStatus</members>\n"
			+ "<members>WorkOrderPriority</members>\n"
			+ "<members>WorkOrderStatus</members>\n"
			+ "<name>StandardValueSet</name>\n"
			+ "</types>\n";
	 */
}