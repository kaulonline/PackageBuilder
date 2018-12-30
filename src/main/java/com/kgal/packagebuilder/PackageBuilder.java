package com.kgal.packagebuilder;

import java.io.File;
import java.io.IOException;
import java.rmi.RemoteException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import javax.xml.transform.TransformerConfigurationException;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.NoFilepatternException;
import org.eclipse.jgit.lib.PersonIdent;
import org.xml.sax.SAXException;

import com.kgal.packagebuilder.inventory.InventoryDatabase;
import com.kgal.packagebuilder.inventory.InventoryItem;
import com.kgal.packagebuilder.output.MetaDataOutput;
import com.kgal.packagebuilder.output.SimpleXMLDoc;
import com.salesforce.migrationtoolutils.Utils;
import com.sforce.soap.metadata.DescribeMetadataObject;
import com.sforce.soap.metadata.DescribeMetadataResult;
import com.sforce.soap.metadata.FileProperties;
import com.sforce.soap.metadata.ListMetadataQuery;
import com.sforce.soap.metadata.MetadataConnection;
import com.sforce.soap.partner.PartnerConnection;
import com.sforce.soap.partner.QueryResult;
import com.sforce.soap.partner.sobject.SObject;
//import com.sforce.soap.partner.PartnerConnection;
//import com.sforce.soap.partner.QueryResult;
//import com.sforce.soap.partner.sobject.SObject;
import com.sforce.soap.tooling.ToolingConnection;
import com.sforce.ws.ConnectionException;

public class PackageBuilder {

    public enum Loglevel {
        VERBOSE(2), NORMAL(1), BRIEF(0);
        private final int level;

        Loglevel(final int level) {
            this.level = level;
        }

        int getLevel() {
            return this.level;
        }

    };

    public enum OperationMode {
        DIR(0), ORG(1);

        private final int level;

        OperationMode(final int level) {
            this.level = level;
        }

        int getLevel() {
            return this.level;
        }
    }

    // Static values that don;t change
    private static final String  DBFILENAMESUFFIX       = ".packageBuilderDB";
    private static final String  DEFAULT_DATE_FORMAT    = "yyyy-MM-dd'T'HH:mm:ss";
    private static final String  URLBASE                = "/services/Soap/u/";
    private static final int     MAXITEMSINPACKAGE      = 10000;
    public static final double   API_VERSION            = 44.0;
    public static final boolean  INCLUDECHANGEDATA      = false;
    private static final boolean FILTERVERSIONLESSFLOWS = true;

    private static final String[] STANDARDVALUETYPESARRAY = new String[] { "AccountContactMultiRoles",
            "AccountContactRole", "AccountOwnership", "AccountRating", "AccountType", "AddressCountryCode",
            "AddressStateCode",
            "AssetStatus", "CampaignMemberStatus", "CampaignStatus", "CampaignType", "CaseContactRole", "CaseOrigin",
            "CasePriority", "CaseReason",
            "CaseStatus", "CaseType", "ContactRole", "ContractContactRole", "ContractStatus", "EntitlementType",
            "EventSubject", "EventType",
            "FiscalYearPeriodName", "FiscalYearPeriodPrefix", "FiscalYearQuarterName", "FiscalYearQuarterPrefix",
            "IdeaCategory1",
            "IdeaMultiCategory", "IdeaStatus", "IdeaThemeStatus", "Industry", "InvoiceStatus", "LeadSource",
            "LeadStatus", "OpportunityCompetitor",
            "OpportunityStage", "OpportunityType", "OrderStatus1", "OrderType", "PartnerRole", "Product2Family",
            "QuestionOrigin1", "QuickTextCategory",
            "QuickTextChannel", "QuoteStatus", "SalesTeamRole", "Salutation", "ServiceContractApprovalStatus",
            "SocialPostClassification",
            "SocialPostEngagementLevel", "SocialPostReviewedStatus", "SolutionStatus", "TaskPriority", "TaskStatus",
            "TaskSubject", "TaskType",
            "WorkOrderLineItemStatus", "WorkOrderPriority", "WorkOrderStatus" };

    // Collections
    private final ArrayList<Pattern>  skipPatterns  = new ArrayList<Pattern>();
    private final HashSet<String>     existingTypes = new HashSet<String>();
    private final Map<String, String> parameters    = new HashMap<String, String>();

    // Variables changing per parameter or properties
    private double                                  myApiVersion;
    private String                                  skipItems;
    private HashMap<String, DescribeMetadataObject> describeMetadataObjectsMap;
    String                                          authEndPoint = "";
    private long                                    timeStart;
    private MetadataConnection                      srcMetadataConnection;
    private ToolingConnection                       srcToolingConnection;
    private String                                  srcUrl;
    private String                                  srcUser;
    private String                                  srcPwd;
    // added for database handling
    private String            dbFilename;
    private String            targetDir = "";
    private Loglevel          loglevel;
    private OperationMode     mode;
    private PartnerConnection srcPartnerConnection;

    private boolean includeChangeData = false;
    private boolean downloadData      = false;
    private boolean gitCommit         = false;

    // Constructor that gets all settings as map
    public PackageBuilder(final Map<String, String> parameters) {
        this.parameters.putAll(parameters);

    }

    private boolean isParamTrue(final String paramName) {
        return "true".equals(this.parameters.get(paramName));
    }

    public void run() throws RemoteException, Exception {

        // set loglevel based on parameters
        if ((this.parameters.get("loglevel") != null) && this.parameters.get("loglevel").equals("verbose")) {
            this.loglevel = Loglevel.NORMAL;
        } else {
            this.loglevel = Loglevel.BRIEF;
        }

        // Check what to do based on parameters
        this.includeChangeData = this.isParamTrue("includechangedata");
        this.downloadData = this.isParamTrue("download");
        this.gitCommit = this.isParamTrue("gitcommit");

        // initialize inventory - it will be used in both types of operations
        // (connect to org or run local)
        // added for inventory database handling

        final HashMap<String, ArrayList<InventoryItem>> inventory = new HashMap<String, ArrayList<InventoryItem>>();
        // HashMap<String,ArrayList<String>> inventory = new
        // HashMap<String,ArrayList<String>>();

        this.myApiVersion = Double.parseDouble(this.parameters.get("apiversion"));
        this.targetDir = Utils.checkPathSlash(Utils.checkPathSlash(this.parameters.get("targetdirectory")));

        // handling for building a package from a directory
        // if we have a base directory set, ignore everything else and generate
        // from the directory

        if (this.parameters.get("basedirectory") != null) {
            this.generateInventoryFromDir(inventory);
            this.mode = OperationMode.DIR;
        } else {
            this.generateInventoryFromOrg(inventory);
            this.mode = OperationMode.ORG;
        }
        final HashMap<String, ArrayList<InventoryItem>> actualInventory = this.generatePackageXML(inventory);

        if (this.downloadData) {
            final Map<String, Set<InventoryItem>> actualChangedFiles = this.downloadMetaData(actualInventory);
            if (this.gitCommit) {
                this.commitToGit(actualChangedFiles);
            }
        }
    }

    // this method reads in any old database that may exist that matches the org
    // then runs the current inventory against that database to generate any
    // updates/deletes
    // and then writes the database file back

    private HashMap<String, ArrayList<InventoryItem>>[] breakPackageIntoFiles(
            final HashMap<String, ArrayList<InventoryItem>> myFile) {

        final ArrayList<HashMap<String, ArrayList<InventoryItem>>> files = new ArrayList<HashMap<String, ArrayList<InventoryItem>>>();
        int fileIndex = 0;
        int fileCount = 0;
        HashMap<String, ArrayList<InventoryItem>> currentFile = new HashMap<String, ArrayList<InventoryItem>>();
        for (final String mdType : myFile.keySet()) {
            final ArrayList<InventoryItem> mdTypeList = myFile.get(mdType);
            final int mdTypeSize = mdTypeList.size();

            // do we have room in this file for the
            if ((fileCount + mdTypeSize) > PackageBuilder.MAXITEMSINPACKAGE) {
                // no, we don't, finish file off, add to list, create new and
                // add to that
                files.add(currentFile);
                currentFile = new HashMap<String, ArrayList<InventoryItem>>();

                this.log("Finished composing file " + fileIndex + ", total count: " + fileCount + "items.",
                        Loglevel.NORMAL);
                fileCount = 0;
                fileIndex++;
            }
            // now add this type to this file and continue
            currentFile.put(mdType, mdTypeList);
            fileCount += mdTypeSize;
            this.log(
                    "Adding type: " + mdType + "(" + mdTypeSize + " items) to file " + fileIndex + ", total count now: "
                            + fileCount,
                    Loglevel.NORMAL);
        }

        // finish off any last file
        files.add(currentFile);
        this.log("Finished composing file " + fileIndex + ", total count: " + fileCount + "items.", Loglevel.NORMAL);

        @SuppressWarnings("unchecked")
        HashMap<String, ArrayList<InventoryItem>>[] retval = new HashMap[files
                .size()];

        retval = files.toArray(retval);

        return retval;
    }

    // this method runs through the inventory, identifies any items that have
    // changed since the database
    // was written and adds the relevant lines to the database

    // TODO: parameterized handling for deletes

    private void commitToGit(final Map<String, Set<InventoryItem>> actualChangedFiles)
            throws IOException, NoFilepatternException, GitAPIException {
        // TODO: Read the correct repository path
        final Git git = Git.open(new File("."));

        for (final String key : actualChangedFiles.keySet()) {
            PersonIdent author = null;
            String commitMessage = null;
            final Set<InventoryItem> curSet = actualChangedFiles.get(key);
            for (final InventoryItem curItem : curSet) {
                // TODO: check if local file name works
                git.add().addFilepattern(curItem.localFileName).call();
                if (author == null) {
                    author = new PersonIdent(curItem.lastModifiedByUsername, curItem.lastModifiedByEmail);
                }
                if (commitMessage == null) {
                    commitMessage = "Change by " + curItem.lastModifiedByEmail + " [AutoRetrieve]";
                }
            }

            git.commit().setMessage(commitMessage).setAuthor(author).call();

        }

    }

    // this method compares the inventory to the database, and adds/updates as
    // needed

    private void doDatabaseUpdate(final InventoryDatabase database,
            final HashMap<String, ArrayList<InventoryItem>> inventory) {

        for (final String metadataType : inventory.keySet()) {
            this.doDatabaseUpdateForAType(metadataType, database, inventory.get(metadataType));
        }

    }

    // added method for generating an inventory based on a local directory
    // rather than an org

    private void doDatabaseUpdateForAType(final String metadataType, final InventoryDatabase database,
            final ArrayList<InventoryItem> inventory) {

        for (final InventoryItem item : inventory) {
            database.addIfNewOrUpdated(metadataType, item);
        }

    }

    private String downloadIfChanged(final String itemType, final InventoryItem oneMetaData) throws IOException {
        final String location = "Where does that file go";
        // Smart Output Stream. Doesn't save if the files are the same
        final MetaDataOutput out = new MetaDataOutput(location);
        // TODO: actually download stuff

        out.flush();
        out.close();
        return (out.isFileSaved() ? location : null);
    }

    private Map<String, Set<InventoryItem>> downloadMetaData(
            final HashMap<String, ArrayList<InventoryItem>> actualInventory) throws IOException {
        final Map<String, Set<InventoryItem>> result = new HashMap<>();

        for (final String itemType : actualInventory.keySet()) {
            final ArrayList<InventoryItem> itemList = actualInventory.get(itemType);
            for (final InventoryItem oneMetaData : itemList) {
                final String downLoadedFileName = this.downloadIfChanged(itemType, oneMetaData);
                if (downLoadedFileName != null) {
                    final String curEmail = oneMetaData.lastModifiedByEmail;
                    final Set<InventoryItem> curItems = (result.containsKey(curEmail)) ? result.get(curEmail)
                            : new HashSet<>();
                    curItems.add(oneMetaData);
                    result.put(curEmail, curItems);
                }
            }
        }

        return result;
    }

    private void endTiming() {
        final long end = System.currentTimeMillis();
        final long diff = ((end - this.timeStart));
        final String hms = String.format("%02d:%02d:%02d", TimeUnit.MILLISECONDS.toHours(diff),
                TimeUnit.MILLISECONDS.toMinutes(diff) - TimeUnit.HOURS.toMinutes(TimeUnit.MILLISECONDS.toHours(diff)),
                TimeUnit.MILLISECONDS.toSeconds(diff)
                        - TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(diff)));
        this.log("Duration: " + hms, Loglevel.NORMAL);
    }

    // inventory is a list of lists
    // keys are the metadata types
    // e.g. flow, customobject, etc.

    private HashMap<String, InventoryItem> fetchMetadataType(final String metadataType)
            throws RemoteException, Exception {
        this.startTiming();
        // logPartialLine(", level);
        final HashMap<String, InventoryItem> packageInventoryList = new HashMap<String, InventoryItem>();
        try {

            final ArrayList<FileProperties> foldersToProcess = new ArrayList<FileProperties>();
            boolean isFolder = false;
            // check if what we have here is in folders

            final DescribeMetadataObject obj = this.describeMetadataObjectsMap.get(metadataType);
            if ((obj != null) && (obj.getInFolder() == true)) {
                isFolder = true;
                this.log(metadataType + " is stored in folders. Getting folder list.", Loglevel.VERBOSE);
                final ListMetadataQuery query = new ListMetadataQuery();
                // stupid hack for emailtemplate folder name
                String type;
                if (metadataType.toLowerCase().equals("emailtemplate")) {
                    type = "EmailFolder";
                } else {
                    type = metadataType + "Folder";
                }

                query.setType(type);
                final FileProperties[] srcMd = this.srcMetadataConnection.listMetadata(
                        new ListMetadataQuery[] { query },
                        this.myApiVersion);
                if ((srcMd != null) && (srcMd.length > 0)) {
                    for (final FileProperties n : srcMd) {
                        foldersToProcess.add(n);
                        // add folder to final inventory
                        packageInventoryList.put(n.getFullName(), new InventoryItem(n.getFullName(), n, true));
                    }
                }
            }

            final Iterator<FileProperties> folder = foldersToProcess.iterator();

            final HashMap<String, ArrayList<FileProperties>> metadataMap = new HashMap<String, ArrayList<FileProperties>>();

            int itemCount = 0;
            // int thisItemCount = 0;

            do {
                FileProperties folderProperties = null;
                final ListMetadataQuery query = new ListMetadataQuery();

                query.setType(metadataType);
                String folderName = null;
                if (isFolder && folder.hasNext()) {
                    folderProperties = folder.next();
                    folderName = folderProperties.getFullName();
                    query.setFolder(folderName);
                }

                // Assuming that the SOAP binding has already been established.

                // generate full metadata inventory

                final FileProperties[] srcMd = this.srcMetadataConnection.listMetadata(
                        new ListMetadataQuery[] { query },
                        this.myApiVersion);
                itemCount += srcMd.length;
                // thisItemCount = srcMd.length;
                if (folderName != null) {
                    this.log("Processing folder: " + folderName + " " + " items: " + srcMd.length + "\tCurrent total: "
                            + itemCount, Loglevel.NORMAL);
                    // fetch folders themselves
                    // packageMap.add(folderName);
                    final ArrayList<FileProperties> filenameList = new ArrayList<FileProperties>();
                    filenameList.add(folderProperties);
                    metadataMap.put(folderProperties.getFileName(), filenameList);
                    itemCount++;
                }

                if (itemCount > 0) {
                    this.existingTypes.add(metadataType);
                }

                if (((srcMd != null) && (srcMd.length > 0))
                        || metadataType.equals("StandardValueSet")) { // hack
                                                                      // alert -
                                                                      // currently
                                                                      // listMetadata
                                                                      // call
                                                                      // will
                                                                      // return
                                                                      // nothing
                                                                      // for
                                                                      // StandardValueSet
                    if (!metadataType.equals("StandardValueSet")) {
                        for (final FileProperties n : srcMd) {
                            if ((n.getNamespacePrefix() == null) || n.getNamespacePrefix().equals("")) {
                                // packageMap.add(n.getFullName());
                                packageInventoryList.put(n.getFullName(), new InventoryItem(n.getFullName(), n));
                            }

                        }
                    } else {
                        for (final String s : PackageBuilder.STANDARDVALUETYPESARRAY) {
                            // packageMap.add(s);
                            packageInventoryList.put(s, new InventoryItem(s, null));
                        }
                    }

                } else {
                    if (!isFolder) {
                        this.log("No items of this type, skipping...", Loglevel.VERBOSE);
                        break;
                    }
                    if (!folder.hasNext()) {
                        break;
                    }
                }
                if ((isFolder == true) && folder.hasNext()) {
                    continue;
                }

            } while (folder.hasNext());

        } catch (final ConnectionException ce) {
            // ce.printStackTrace();
            this.log("\nException processing: " + metadataType, Loglevel.BRIEF);
            this.log("Error: " + ce.getMessage(), Loglevel.BRIEF);
        }

        this.endTiming();
        return packageInventoryList;
    }

    private Vector<String> generateFileList(final File node, final String baseDir) {

        final Vector<String> retval = new Vector<String>();
        // add file only
        if (node.isFile()) {
            retval.add(this.generateZipEntry(node.getAbsoluteFile().toString(), baseDir));
            // retval.add(baseDir + "/" + node.getAbsoluteFile().toString());
            // retval.add(node.getName());
        } else if (node.isDirectory()) {
            final String[] subNote = node.list();
            for (final String filename : subNote) {
                retval.addAll(this.generateFileList(new File(node, filename), baseDir));
            }
        }
        return retval;
    }

    /*
     *
     * this method will populate username (Salesforce user name in email format)
     * and user email fields on the inventoryItems for use when outputting
     * change telemetry
     *
     */

    private void generateInventoryFromDir(final HashMap<String, ArrayList<InventoryItem>> inventory)
            throws IOException {
        final String basedir = this.parameters.get("basedirectory");

        // check if the directory is valid

        final HashMap<String, HashSet<InventoryItem>> myInventory = new HashMap<String, HashSet<InventoryItem>>();

        if (!Utils.checkIsDirectory(basedir)) {
            // log error and exit

            this.log("Base directory parameter provided: " + basedir
                    + " invalid or is not a directory, cannot continue.",
                    Loglevel.BRIEF);
            System.exit(1);
        }

        // directory valid - enumerate and generate inventory

        final Vector<String> filelist = this.generateFileList(new File(basedir), basedir);

        // so now we have a list of folders/files
        // need to convert to inventory for package.xml generator

        for (final String s : filelist) {
            // ignore -meta.xml

            if (s.contains("-meta.xml")) {
                continue;
            }

            // split into main folder + rest

            try {

                // ignore anything which doesn't have a path separator (i.e. not
                // a folder)

                final int separatorLocation = s.indexOf(File.separator);

                if (separatorLocation == -1) {
                    this.log("No folder in: " + s + ",skipping...", Loglevel.VERBOSE);
                    continue;
                }

                final String foldername = s.substring(0, separatorLocation);
                String filename = s.substring(separatorLocation + 1);

                // split off file name suffix

                filename = filename.substring(0, filename.lastIndexOf("."));

                // ignore anything starting with a .

                if (filename.startsWith(".")) {
                    continue;
                }

                // figure out based on foldername what the metadatatype is

                String mdType = Utils.getMetadataTypeForDir(foldername);

                // if not found, try lowercase
                if (mdType == null) {
                    mdType = Utils.getMetadataTypeForDir(foldername.toLowerCase());
                }

                if (mdType == null) {
                    this.log("Couldn't find type mapping for item : " + mdType + " : " + filename + ", original path: "
                            + s
                            + ",skipping...", Loglevel.BRIEF);
                    continue;
                }

                // generate inventory entry

                HashSet<InventoryItem> typeInventory = myInventory.get(mdType);
                if (typeInventory == null) {
                    typeInventory = new HashSet<InventoryItem>();
                    myInventory.put(mdType, typeInventory);
                    System.out.println("Created inventory record for type: " + mdType);
                }

                // check if there is a folder in the filename and it's aura -
                // then we need to leave the folder, skip the item

                if (filename.contains("/") && mdType.equals("AuraDefinitionBundle")) {
                    final String subFoldername = filename.substring(0, filename.indexOf("/"));
                    typeInventory.add(new InventoryItem(subFoldername, null));
                    this.log("Added: " + mdType + " : " + subFoldername + ", to inventory, original path: " + s,
                            Loglevel.NORMAL);
                    continue;
                }

                // check if there is a folder in the filename - then we need to
                // add the folder as well

                if (filename.contains("/")) {
                    final String subFoldername = filename.substring(0, filename.indexOf("/"));
                    typeInventory.add(new InventoryItem(subFoldername, null));
                }

                typeInventory.add(new InventoryItem(filename, null));
                this.log("Added: " + mdType + " : " + filename + ", to inventory, original path: " + s,
                        Loglevel.NORMAL);

                // convert myinventory to the right return type

            } catch (final Exception e) {
                // Something bad happened
                System.out.println("Something bad happened on file: " + s + ", skipping...");
            }

        }
        for (final String myMdType : myInventory.keySet()) {
            final ArrayList<InventoryItem> invType = new ArrayList<InventoryItem>();
            invType.addAll(myInventory.get(myMdType));
            inventory.put(myMdType, invType);
        }

        //

    }

    private void generateInventoryFromOrg(final HashMap<String, ArrayList<InventoryItem>> inventory)
            throws RemoteException, Exception {

        // Initialize the metadata connection we're going to need

        this.srcUrl = this.parameters.get("serverurl") + PackageBuilder.URLBASE + this.myApiVersion;
        this.srcUser = this.parameters.get("username");
        this.srcPwd = this.parameters.get("password");
        this.skipItems = this.parameters.get("skipItems");
        // Make a login call to source
        this.srcMetadataConnection = LoginUtil.mdLogin(this.srcUrl, this.srcUser, this.srcPwd);

        // Figure out what we are going to be fetching

        final ArrayList<String> workToDo = new ArrayList<String>(this.getTypesToFetch());
        Collections.sort(workToDo);

        this.log("Will fetch: " + String.join(", ", workToDo) + " from: " + this.srcUrl, Loglevel.BRIEF);
        this.log("Using user: " + this.srcUser + " skipping: " + this.skipItems, Loglevel.NORMAL);

        System.out.println("target directory: " + this.targetDir);

        Utils.checkDir(this.targetDir);

        final Iterator<String> i = workToDo.iterator();
        int counter = 0;
        while (i.hasNext()) {
            counter++;
            final String mdType = i.next();
            if (this.loglevel.getLevel() > Loglevel.BRIEF.getLevel()) {
                this.log("*********************************************", Loglevel.NORMAL);
                this.log("Processing type " + counter + " out of " + workToDo.size() + ": " + mdType, Loglevel.NORMAL);
                this.log("*********************************************", Loglevel.NORMAL);
            } else if (this.loglevel == Loglevel.BRIEF) {
                this.logPartialLine("Processing type " + counter + " out of " + workToDo.size() + ": " + mdType,
                        Loglevel.BRIEF);
            }

            final ArrayList<InventoryItem> mdTypeItemList = new ArrayList<InventoryItem>(
                    this.fetchMetadataType(mdType).values());
            Collections.sort(mdTypeItemList, (o1, o2) -> o1.itemName.compareTo(o2.itemName));
            inventory.put(mdType, mdTypeItemList);

            if (this.loglevel.getLevel() > Loglevel.BRIEF.getLevel()) {
                this.log("---------------------------------------------", Loglevel.NORMAL);
                this.log("Finished processing: " + mdType, Loglevel.NORMAL);
                this.log("---------------------------------------------", Loglevel.NORMAL);
            } else if (this.loglevel == Loglevel.BRIEF) {
                this.log(" items: " + mdTypeItemList.size(), Loglevel.BRIEF);
            }
        }

    }

    private HashMap<String, ArrayList<InventoryItem>> generatePackageXML(
            final HashMap<String, ArrayList<InventoryItem>> inventory)
            throws ConnectionException, IOException, TransformerConfigurationException, SAXException {

        int itemCount = 0;
        int skipCount = 0;

        final HashMap<String, ArrayList<InventoryItem>> myFile = new HashMap<String, ArrayList<InventoryItem>>();

        final ArrayList<String> types = new ArrayList<String>();
        types.addAll(inventory.keySet());
        Collections.sort(types);

        for (final String mdType : types) {

            // check if we have any items in this category

            final ArrayList<InventoryItem> items = inventory.get(mdType);
            if (items.size() < 1) {
                continue;
            }

            myFile.put(mdType, new ArrayList<InventoryItem>());

            Collections.sort(items, (o1, o2) -> o1.itemName.compareTo(o2.itemName));
            for (final InventoryItem item : items) {

                // special treatment for flows
                // get rid of items returned without a version number
                // <members>Update_Campaign_path_on_oppty</members> **** FILTER
                // THIS ONE OUT SO IT DOESN'T APPEAR***
                // <members>Update_Campaign_path_on_oppty-4</members>
                // <members>Update_Campaign_path_on_oppty-5</members>

                if (mdType.toLowerCase().equals("flow") && PackageBuilder.FILTERVERSIONLESSFLOWS) {
                    if (!item.itemName.contains("-")) {
                        // we won't count this one as skipped, since it
                        // shouldn't be there in the first place
                        continue;
                    }
                }
                myFile.get(mdType).add(item);
                itemCount++;
            }

            // special treatment for flows
            // make a callout to Tooling API to get latest version for Active
            // flows (which the shi+ Metadata API won't give you)

            // only do this if we're running in org mode

            if (mdType.toLowerCase().equals("flow") && (this.mode == OperationMode.ORG)) {

                /*
                 * skip flow handling for now
                 *
                 * String flowQuery =
                 * "SELECT DeveloperName ,ActiveVersion.VersionNumber " +
                 * "FROM FlowDefinition " +
                 * "WHERE ActiveVersion.VersionNumber <> NULL";
                 *
                 * this.srcToolingConnection = LoginUtil.toolingLogin(srcUrl,
                 * srcUser, srcPwd); com.sforce.soap.tooling.QueryResult qr =
                 * srcToolingConnection.query(flowQuery);
                 * com.sforce.soap.tooling.sobject.SObject[] records =
                 * qr.getRecords(); for (com.sforce.soap.tooling.sobject.SObject
                 * record : records) {
                 * com.sforce.soap.tooling.sobject.FlowDefinition fd =
                 * (com.sforce.soap.tooling.sobject.FlowDefinition) record;
                 * myFile.get(mdType).add(fd.getDeveloperName() + "-" +
                 * fd.getActiveVersion().getVersionNumber()); itemCount++; }
                 */
            }
        }

        // now check if anything we have needs to be skipped

        skipCount = this.handleSkippingItems(myFile);

        // now break it up into files if needed

        final HashMap<String, ArrayList<InventoryItem>>[] files = this.breakPackageIntoFiles(myFile);

        // if we're writing change telemetry into the package.xml, need to get
        // user emails now

        this.populateUserEmails(myFile);

        for (int i = 0; i < files.length; i++) {
            if (i == 0) {
                this.writePackageXmlFile(files[i], "package.xml");
            } else {
                this.writePackageXmlFile(files[i], "package." + i + ".xml");
            }
        }

        final ArrayList<String> typesFound = new ArrayList<String>(this.existingTypes);
        Collections.sort(typesFound);

        this.log("Types found in org: " + typesFound.toString(), Loglevel.BRIEF);

        this.log("Total items in package.xml: " + itemCount, Loglevel.BRIEF);
        this.log("Total items skipped: " + skipCount
                + " (excludes count of items in type where entire type was skipped)",
                Loglevel.NORMAL);

        return myFile;

    }

    private String generateZipEntry(final String file, final String sourceFolder) {
        final int indexOfSourceFolder = file.lastIndexOf(sourceFolder);
        return file.substring(indexOfSourceFolder + sourceFolder.length() + 1, file.length());
    }

    private InventoryDatabase getDatabase(final String orgId) {

        InventoryDatabase newDatabase = null;
        final boolean databaseFileExists = false;

        // TODO find a database if it exists

        // placeholder for loading database file if it exists
        if (databaseFileExists) {
            newDatabase = InventoryDatabase.readDatabaseFromFile(this.dbFilename);
            // TODO confirm that the orgid matches
        } else {
            newDatabase = new InventoryDatabase(orgId);
        }
        return newDatabase;
    }

    private String getOrgIdentifier() {
        // TODO Auto-generated method stub
        return "myOrg";
    }

    private HashSet<String> getTypesToFetch() throws ConnectionException {

        final HashSet<String> typesToFetch = new HashSet<String>();
        final String mdTypesToExamine = this.parameters.get("metadataitems");

        // get a describe

        final DescribeMetadataResult dmr = this.srcMetadataConnection.describeMetadata(this.myApiVersion);
        this.describeMetadataObjectsMap = new HashMap<String, DescribeMetadataObject>();

        for (final DescribeMetadataObject obj : dmr.getMetadataObjects()) {
            this.describeMetadataObjectsMap.put(obj.getXmlName(), obj);
        }

        // if a metadataitems parameter was provided, we use that

        if (mdTypesToExamine != null) {
            for (final String s : mdTypesToExamine.split(",")) {
                typesToFetch.add(s.trim());
            }
        } else {
            // no directions on what to fetch - go get everything
            this.log("No metadataitems (-mi) parameter found, will inventory the whole org", Loglevel.BRIEF);

            for (final String obj : this.describeMetadataObjectsMap.keySet()) {
                typesToFetch.add(obj.trim());
            }
        }
        return typesToFetch;
    }

    private int handleSkippingItems(final HashMap<String, ArrayList<InventoryItem>> myFile) {

        int skipCount = 0;

        // Initiate patterns array
        // TODO: handle non-existent parameter in the config files

        if (this.skipItems != null) {
            for (final String p : this.skipItems.split(",")) {
                try {
                    this.skipPatterns.add(Pattern.compile(p));
                } catch (final PatternSyntaxException e) {
                    System.out.println("Tried to compile pattern: " + p + " but got exception: ");
                    e.printStackTrace();
                }
            }
        }

        for (final String mdType : myFile.keySet()) {
            // first, check if any of the patterns match the whole type
            String mdTypeFullName = mdType + ":";
            for (final Pattern p : this.skipPatterns) {

                final Matcher m = p.matcher(mdTypeFullName);
                if (m.matches()) {
                    this.log("Skip pattern: " + p.pattern() + " matches the metadata type: " + mdTypeFullName
                            + ", entire type will be skipped.", Loglevel.NORMAL);

                    // remove the whole key from the file

                    skipCount += myFile.get(mdType).size();

                    myFile.remove(mdType);
                    continue;

                }
            }

            final ArrayList<InventoryItem> items = myFile.get(mdType);
            Collections.sort(items, (o1, o2) -> o1.itemName.compareTo(o2.itemName));
            for (int i = 0; i < items.size(); i++) {
                mdTypeFullName = mdType + ":" + items.get(i).itemName;
                for (final Pattern p : this.skipPatterns) {
                    final Matcher m = p.matcher(mdTypeFullName);
                    if (m.matches()) {
                        this.log("Skip pattern: " + p.pattern() + " matches the metadata item: " + mdTypeFullName
                                + ", item will be skipped.", Loglevel.NORMAL);
                        items.remove(i);
                        skipCount++;
                    }
                }
            }
        }
        return skipCount;
    }

    private void log(final String logText, final Loglevel level) {
        if ((this.loglevel == null) || (level.getLevel() <= this.loglevel.getLevel())) {
            System.out.println(logText);
        }
    }

    private void logPartialLine(final String logText, final Loglevel level) {
        if (level.getLevel() <= this.loglevel.getLevel()) {
            System.out.print(logText);
        }
    }

    private void populateUserEmails(final HashMap<String, ArrayList<InventoryItem>> myFile) throws ConnectionException {

        final Set<String> userIDs = new HashSet<String>();

        for (final String mdName : myFile.keySet()) {
            for (final InventoryItem i : myFile.get(mdName)) {
                userIDs.add(i.getLastModifiedById());
            }
        }

        // now call salesforce to get the emails and usernames

        final HashMap<String, HashMap<String, String>> usersBySalesforceID = new HashMap<String, HashMap<String, String>>();

        // login
        this.srcPartnerConnection = LoginUtil.soapLogin(this.srcUrl, this.srcUser, this.srcPwd);

        // build the query

        final String queryStart = "SELECT Id, Name, Username, Email FROM User WHERE ID IN(";
        final String queryEnd = ")";
        final String[] myIDs = userIDs.toArray(new String[userIDs.size()]);
        final String queryMid = "'" + String.join("','", myIDs) + "'";

        final String query = queryStart + queryMid + queryEnd;

        this.log("Looking for emails for " + userIDs.size() + " users.", Loglevel.BRIEF);
        this.log("Query: " + query, Loglevel.NORMAL);

        // run the query

        QueryResult qResult = this.srcPartnerConnection.query(query);

        boolean done = false;
        if (qResult.getSize() > 0) {
            System.out.println("Logged-in user can see a total of "
                    + qResult.getSize() + " contact records.");
            while (!done) {
                final SObject[] records = qResult.getRecords();
                for (final SObject o : records) {
                    final HashMap<String, String> userMap = new HashMap<String, String>();
                    userMap.put("Name", (String) o.getField("Name"));
                    userMap.put("Email", (String) o.getField("Email"));
                    userMap.put("Username", (String) o.getField("Username"));
                    usersBySalesforceID.put((String) o.getField("Id"), userMap);
                }
                if (qResult.isDone()) {
                    done = true;
                } else {
                    qResult = this.srcPartnerConnection.queryMore(qResult.getQueryLocator());
                }
            }
        } else {
            System.out.println("No records found.");
        }

        // now run through the InventoryItems again and update user data

        for (final String mdName : myFile.keySet()) {
            for (final InventoryItem i : myFile.get(mdName)) {
                i.lastModifiedByEmail = usersBySalesforceID.get(i.getLastModifiedById()).get("Email");
                i.lastModifiedByUsername = usersBySalesforceID.get(i.getLastModifiedById()).get("Username");
            }
        }

    }

    private void startTiming() {
        this.timeStart = System.currentTimeMillis();
    }

    private void updateDatabase(final HashMap<String, ArrayList<InventoryItem>> inventory) {
        // construct org identified
        final String orgId = this.getOrgIdentifier();

        // read in old database (if any), generate one if not
        final InventoryDatabase database = this.getDatabase(orgId);

        // run through current inventory, compare against db
        this.doDatabaseUpdate(database, inventory);

        // write out new records to be added to database

        for (final String type : database.getUpdatedItemsDatabase().keySet()) {
            for (final InventoryItem i : database.getUpdatedItemsDatabase().get(type)) {
                System.out.println((i.isNew ? "New: " : "Updated: ") + i.toCSV());
            }
        }

        // output any new records to screen

    }

    /*
     * private void writePackageXmlFile(HashMap<String, ArrayList<String>>
     * theMap, String filename)
     *
     * this method will generate a package.xml file based on a HashMap<String,
     * ArrayList<String>> and a filename used to cater for big orgs that burst
     * limits input hashmap structure: ApexClass -> [Class1,Class2] ApexTrigger
     * -> [Trigger1,Trigger2]
     *
     * expecting only to see types which are populated (i.e. have at least 1
     * item)
     *
     * Expected output: <types> <name>ApexClass</name> <members>Class1</members>
     * <members>Class2</members> </types> <types> <name>ApexTrigger</name>
     * <members>Trigger1</members> <members>Trigger2</members> </types>
     *
     */
    private void writePackageXmlFile(final HashMap<String, ArrayList<InventoryItem>> theMap, final String filename)
            throws IOException, TransformerConfigurationException, SAXException {

        final SimpleDateFormat format1 = new SimpleDateFormat(PackageBuilder.DEFAULT_DATE_FORMAT);

        final SimpleXMLDoc packageXML = new SimpleXMLDoc();
        packageXML.openTag("Package", "xmlns", "http://soap.sforce.com/2006/04/metadata");

        final ArrayList<String> mdTypes = new ArrayList<String>(theMap.keySet());
        Collections.sort(mdTypes);

        for (final String mdType : mdTypes) {
            packageXML.openTag("<types>");
            packageXML.addTag("name", mdType);

            for (final InventoryItem item : theMap.get(mdType)) {

                Map<String, String> attributes = null;
                if (this.includeChangeData) {
                    attributes = new HashMap<>();
                    attributes.put("lastmodifiedby", item.getLastModifiedByName());
                    attributes.put("lastmodified", format1.format(item.getLastModifiedDate().getTime()));
                    attributes.put("lastmodifiedemail", item.lastModifiedByEmail);
                }

                packageXML.addTag("members", item.itemName, attributes);
            }
            packageXML.closeTag(1);
        }
        packageXML.addTag("version", String.valueOf(this.myApiVersion));
        packageXML.closeDocument();

        Utils.writeFile(this.targetDir + filename, packageXML.toString());
        this.log("Writing " + new File(this.targetDir + filename).getCanonicalPath(), Loglevel.BRIEF);
    }

}
