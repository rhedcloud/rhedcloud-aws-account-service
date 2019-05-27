
package edu.emory.awsaccount.service;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.text.SimpleDateFormat;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.TextMessage;

import org.apache.commons.io.FileUtils;
import org.apache.log4j.Logger;
import org.jdom.Document;
import org.jdom.Element;
import org.openeai.config.CommandConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.EnterpriseFieldException;
import org.openeai.jms.consumer.commands.CommandException;
import org.openeai.jms.consumer.commands.SyncCommand;
import org.openeai.jms.consumer.commands.SyncCommandImpl;
import org.openeai.jms.producer.MessageProducer;
import org.openeai.jms.producer.PointToPointProducer;
import org.openeai.jms.producer.ProducerPool;
import org.openeai.moa.EnterpriseObjectQueryException;
import org.openeai.moa.XmlEnterpriseObject;
import org.openeai.moa.objects.testsuite.TestId;
import org.openeai.transport.RequestService;
import org.openeai.xml.XmlDocumentReader;
import org.openeai.xml.XmlDocumentReaderException;

import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.Account;
import com.amazon.aws.moa.objects.resources.v1_0.AccountQuerySpecification;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

import edu.emory.moa.jmsobjects.identity.v1_0.DirectoryPerson;
import edu.emory.moa.objects.resources.v1_0.DirectoryPersonQuerySpecification;

/**
 * For each Account Create-Sync, Delete-Sync or meaningful Update-Sync, this
 * command will query all Account message and create a .csv file. It also query
 * DirectoryService for name and email address of owner, createUser, and
 * lastUpdateUser and added to the csv file.
 * 
 * @author gwang28
 *
 */
public class AccountCsvSyncCommand extends SyncCommandImpl implements SyncCommand {
    private static Logger logger = Logger.getLogger(AccountCsvSyncCommand.class);
    private static String LOGTAG = "[AccountCsvSyncCommand] ";
    protected static int requestTimeoutIntervalMilli = -1;
    protected ProducerPool awsAccountServiceRequestProducerPool;
    protected ProducerPool directoryServiceProducerPool;
    private boolean _verbose;
    protected ProducerPool _producerPool = null;
    private static final String GENERAL_PROPERTIES = "GeneralProperties";
    private SimpleDateFormat simpleDateFormat = null;
    private File tempDir = new File("temp");
    protected static String deletedAccountsFileName = "DeletedAccounts.csv";
    private S3Helper s3Helper;
    // private boolean cleanTempDir = true;

    CacheLoader<String, DirectoryPerson> loader = new CacheLoader<String, DirectoryPerson>() {
        @Override
        public DirectoryPerson load(String key) {
            if (key == null || key.length() == 0)
                return null;
            MessageProducer messageProducer = null;
            List<DirectoryPerson> accounts = new ArrayList<>();
            try {
                DirectoryPerson diretoryPerson = (DirectoryPerson) getAppConfig().getObjectByType(DirectoryPerson.class.getName());
                DirectoryPersonQuerySpecification diretoryPersonQuerySpecification = (DirectoryPersonQuerySpecification) getAppConfig()
                        .getObjectByType(DirectoryPersonQuerySpecification.class.getName());
                if (key.startsWith("P"))
                    diretoryPersonQuerySpecification.setKey(key);
                else
                    diretoryPersonQuerySpecification.setSearchString(key);
                messageProducer = getRequestServiceMessageProducer(directoryServiceProducerPool);
                try {
                    accounts = diretoryPerson.query(diretoryPersonQuerySpecification, (RequestService) messageProducer);
                } catch (Exception e) {
                    if (accounts == null || accounts.isEmpty()) {
                        diretoryPersonQuerySpecification.setKey(null);
                        diretoryPersonQuerySpecification.setSearchString(key);
                        messageProducer = getRequestServiceMessageProducer(directoryServiceProducerPool);
                        accounts = diretoryPerson.query(diretoryPersonQuerySpecification, (RequestService) messageProducer);
                    }
                }
            } catch (Throwable e) {
                logger.error(LOGTAG, e);
            } finally {
                directoryServiceProducerPool.releaseProducer(messageProducer);
            }
            if (accounts == null || accounts.size() == 0)
                return null;
            return accounts.get(0);
        }
    };

    LoadingCache<String, DirectoryPerson> directoryPersonCache = CacheBuilder.newBuilder().build(loader);

    public AccountCsvSyncCommand(CommandConfig cConfig) throws InstantiationException, EnterpriseConfigurationObjectException {
        super(cConfig);
        logger.info("AccountCsvSyncCommand, initializing... ");
        try {
            setProperties(getAppConfig().getProperties(GENERAL_PROPERTIES));
            awsAccountServiceRequestProducerPool = (ProducerPool) getAppConfig().getObject("AwsAccountServiceProducerPool");
            directoryServiceProducerPool = (ProducerPool) getAppConfig().getObject("DirectoryServiceProducerPool");
            _verbose = new Boolean(getProperties().getProperty("verbose", "true")).booleanValue();
            // cleanTempDir = new
            // Boolean(getProperties().getProperty("cleanTempDir",
            // "true")).booleanValue();
            simpleDateFormat = new SimpleDateFormat(getProperties().getProperty("simpleDateFormat", "yyyy-MM-dd-HH.mm.ss"));

            tempDir.mkdir();
            s3Helper = new S3Helper(getProperties());
        } catch (Exception e) {
            throw new InstantiationException(LOGTAG + e.getMessage());
        }
        try {
            _producerPool = (ProducerPool) getAppConfig().getObject("SyncPublisher");

        } catch (Exception e) {
            logger.warn("No 'SyncPublisher' PubSubProducer found in AppConfig.  "
                    + "Processing will continue but Sync Messages will not be published " + "when changes are made via this Command.");
        }
        logger.info(ReleaseTag.getReleaseInfo());
        logger.info("AccountCsvSyncCommand, initialized successfully.");
    }

    @Override
    public void execute(int messageNumber, Message aMessage) throws CommandException {
        logger.info(LOGTAG + "execution begins...");
        Document inDoc = null;
        TextMessage textMessage = (TextMessage) aMessage;
        try {
            inDoc = new XmlDocumentReader().initializeDocument(new StringReader(textMessage.getText()), false);
        } catch (XmlDocumentReaderException | JMSException e1) {
            logger.error(e1);
        }
        Element controleArea = getControlArea(inDoc.getRootElement());
        String msgAction = controleArea.getAttribute("messageAction").getValue();
        Element dataArea = inDoc.getRootElement().getChild("DataArea");
        Element newData = dataArea.getChild("NewData");
        Element deleteData = dataArea.getChild("DeleteData");

        Element baseLineData = dataArea.getChild("BaselineData");
        String msgObject = controleArea.getAttribute("messageObject").getValue();
        String msgObjectName = msgObject + ".v" + controleArea.getAttribute("messageRelease").getValue().replace(".", "_");
        Element objectElement = (newData == null) ? deleteData.getChild(msgObject) : newData.getChild(msgObject);
        logger.info("msgObjectName=" + msgObjectName + ",msgAction=" + msgAction);
        if (!msgObject.equals(Account.class.getSimpleName())) {
            logger.info(LOGTAG + "this command only cares about Account Sync objecct. We are done here.");
            return;
        }

        Element eSender = controleArea.getChild(SENDER);
        if (eSender != null) {
            Element eTestId = eSender.getChild(TEST_ID);
            if (eTestId != null) {
                try {
                    TestId testId = (TestId) getAppConfig().getObject(TEST_ID);
                    testId.buildObjectFromInput(eTestId);
                    if (_verbose) {
                        logger.info("TestId of consumed Message: " + testId.getTestSeriesNumber() + "-" + testId.getTestCaseNumber() + "-"
                                + testId.getTestStepNumber());
                    }
                } catch (Exception e) {
                    if (_verbose) {
                        logger.info("Found a TestId Element in the message consumed but " + "can't build a TestId object.  Continuing.");
                    }
                }
            }
        }
        Account account = null;
        Account accountBaseline = null;
        String senderAppId = controleArea.getChild("Sender").getChild("MessageId").getChild("SenderAppId").getValue();
        Element eAuthUserId = controleArea.getChild("Sender").getChild("Authentication").getChild("AuthUserId");
        String authUserId = eAuthUserId.getValue();
        String authUser = parseAuthUser(authUserId);
        try {
            account = (Account) retrieveAndBuildObject("New/DeleteData", msgObject, msgObjectName, objectElement);
        } catch (Throwable e) {
            logger.error(e);
        }

        if (baseLineData != null) {
            try {
                accountBaseline = (Account) retrieveAndBuildObject("Baseline", msgObject, msgObjectName, baseLineData.getChild(msgObject));
            } catch (Exception e) {
                logger.error(e);
                return;
            }
        }

        if (msgAction.equals("Update")) {
            String financialAccountNumber = account.getFinancialAccountNumber() == null ? "" : account.getFinancialAccountNumber();
            String financialAccountNumberBaseline = accountBaseline.getFinancialAccountNumber() == null ? ""
                    : accountBaseline.getFinancialAccountNumber();
            if (financialAccountNumber.equals(financialAccountNumberBaseline)
                    && account.getAccountOwnerId().equals(accountBaseline.getAccountOwnerId())) {
                logger.info(LOGTAG + "No meaningful data change- we are done here.");
                return;
            }
        }

        if (msgAction.equals("Delete")) {
            List<String[]> deletedAccountDataLines = s3Helper.readDeletedAccounts(getDeletedAccountsFileNameFull());
            deletedAccountDataLines.add(AccountCsvRow.fromAccount(account, directoryPersonCache, authUser).toStrings());
            // TODO exclusive write???
            try {
                s3Helper.writeDeletedAccounts(deletedAccountDataLines, getDeletedAccountsFileNameFull());
            } catch (IOException e) {
                logger.error(LOGTAG, e);
            }
        }
        List<String[]> deletedAccountDataLines = s3Helper.readDeletedAccounts(getDeletedAccountsFileNameFull());
        logger.info("deletedAccountDataLines.size()=" + deletedAccountDataLines.size());
        try {
            List<Account> accounts = queryAllAccounts();
            logger.info(LOGTAG + "accounts.size=" + accounts.size());
            List<AccountCsvRow> accountCsvs = accountsToAccountCsvs(accounts);
            logger.info(LOGTAG + "accountCsvs.size=" + accountCsvs.size());
            List<String[]> dataLines = accountCsvsToDatalines(accountCsvs);
            dataLines.addAll(deletedAccountDataLines);
            String fileName = getDeployEnv() + "." + simpleDateFormat.format(new Date()) + ".csv";
            s3Helper.toCsvFileAndUploadToS3(dataLines, fileName);
        } catch (EnterpriseConfigurationObjectException | EnterpriseObjectQueryException | EnterpriseFieldException | IOException e) {
            logger.error(LOGTAG, e);
        }
    }

    private String getDeletedAccountsFileNameFull() {
        return getDeployEnv() + "-" + deletedAccountsFileName;
    }

    protected static String parseAuthUser(String authUserId) {
        String authUser = "";
        if (authUserId != null) {
            authUser = authUserId;
            if (authUserId.contains("/")) {
                authUser = authUserId.substring(0, authUserId.indexOf("/"));
            }
        }
        return authUser;
    }

    private List<String[]> accountCsvsToDatalines(List<AccountCsvRow> accountCsvs) {
        List<String[]> dataLines = new ArrayList<>();
        dataLines.add(TITLE.toStrings());
        for (AccountCsvRow accountCsv : accountCsvs) {
            dataLines.add(accountCsv.toStrings());
        }
        return dataLines;
    }

    private List<AccountCsvRow> accountsToAccountCsvs(List<Account> accounts) {
        List<AccountCsvRow> accountCsvs = new ArrayList<>();
        for (Account a : accounts) {
            AccountCsvRow accountCsv = AccountCsvRow.fromAccount(a, directoryPersonCache);
            accountCsvs.add(accountCsv);
        }
        return accountCsvs;
    }

    // public void toCsvFileAndUploadToS3(List<String[]> dataLines) throws
    // IOException {
    // FileUtils.cleanDirectory(tempDir);
    // String fileName = getDeployEnv() + "." + simpleDateFormat.format(new
    // Date()) + ".csv";
    // logger.info("fileName=" + fileName);
    // File csvOutputFile = new File(tempDir + "/" + fileName);
    // try (PrintWriter pw = new PrintWriter(csvOutputFile)) {
    // dataLines.stream().map(AccountCsvSyncCommand::convertToCSV).forEach(pw::println);
    // pw.close();
    // }
    // s3Helper.execute(fileName, csvOutputFile.getAbsolutePath());
    // }
    // public static String convertToCSV(String[] data) {
    // return Stream.of(data).collect(Collectors.joining(","));
    // }

    public static String getDeployEnv() {
        // docUriBase.dev=https://dev-config.app.emory.edu/
        // docUriBase.qa=https://qa-config.app.emory.edu/
        // docUriBase.stage=https://staging-config.app.emory.edu/
        // docUriBase.prod=https://config.app.emory.edu
        String docUriBase = System.getProperty("docUriBase").trim();
        if (docUriBase != null && docUriBase.length() > 0) {
            if (docUriBase.startsWith("https://dev"))
                return "DEV";
            if (docUriBase.startsWith("https://qa"))
                return "TEST";
            if (docUriBase.startsWith("https://staging"))
                return "STAGE";
            if (docUriBase.startsWith("https://config"))
                return "PROD";
        }
        return "LOCAL";
    }

    private List<Account> queryAllAccounts()
            throws EnterpriseConfigurationObjectException, EnterpriseObjectQueryException, EnterpriseFieldException {
        Account account = (Account) getAppConfig().getObjectByType(Account.class.getName());
        AccountQuerySpecification accountQuerySpecification = (AccountQuerySpecification) getAppConfig()
                .getObjectByType(AccountQuerySpecification.class.getName());
        MessageProducer messageProducer = getRequestServiceMessageProducer(awsAccountServiceRequestProducerPool);
        List<Account> accounts = new ArrayList<>();
        try {
            accounts = account.query(accountQuerySpecification, (RequestService) messageProducer);
        } finally {
            awsAccountServiceRequestProducerPool.releaseProducer(messageProducer);
        }
        return accounts;
    }

    protected MessageProducer getRequestServiceMessageProducer(ProducerPool producerPool) {
        MessageProducer producer = null;
        try {
            producer = producerPool.getExclusiveProducer();
            if (producer instanceof PointToPointProducer) {
                PointToPointProducer p2pp = (PointToPointProducer) producer;
                if (requestTimeoutIntervalMilli != -1) {
                    p2pp.setRequestTimeoutInterval(requestTimeoutIntervalMilli);
                }
            }
        } catch (JMSException jmse) {
            String errMsg = "An error occurred getting a request service. The " + "exception is: " + jmse.getMessage();
            logger.fatal(LOGTAG + errMsg, jmse);
            throw new java.lang.UnsupportedOperationException(errMsg, jmse);
        }
        return producer;
    }

    private XmlEnterpriseObject retrieveAndBuildObject(String comment, String msgObject, String msgObjectName, Element eData)
            throws CommandException {
        XmlEnterpriseObject xeo = null;
        logger.debug("msgObject=" + msgObject + ", msgObjectName=" + msgObjectName);

        try {
            xeo = (XmlEnterpriseObject) getAppConfig().getObject(msgObjectName);
            logger.debug("Retrieved object '" + msgObjectName + "' from AppConfig.");
        } catch (Exception e) {
            try {
                logger.warn("Could not find object named '" + msgObjectName + "' in AppConfig, trying '" + msgObject + "'");
                xeo = (XmlEnterpriseObject) getAppConfig().getObject(msgObject);
                logger.debug("Retrieved object '" + msgObject + "' from AppConfig.");
            } catch (Throwable e2) {
                logger.warn(e2);
                String msg = "Could not find an object named '" + msgObject + "' OR '" + msgObjectName + "' in AppConfig.  Exception: "
                        + e2.getMessage();
                throw new CommandException(msg, e);
            }
        }

        logger.debug("buildObjectFromInput()...");
        try {
            xeo.buildObjectFromInput(eData);
            if (comment != null) {
                logger.debug(comment + " Object is: " + xeo.toXmlString());
            } else {
                logger.debug("Object is: " + xeo.toXmlString());
            }
        } catch (Throwable e) {
            logger.error(e);
            throw new CommandException(e.getMessage(), e);
        }
        return xeo;
    }
}

class AccountCsvRow {
    private static Logger logger = Logger.getLogger(AccountCsvRow.class);
    private static String LOGTAG = "[AccountCsvRow] ";
    private static SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    public static AccountCsvRow fromAccount(Account a, LoadingCache<String, DirectoryPerson> directoryPersonCache) {
        logger.debug(LOGTAG + "account=" + a.getAccountName());
        AccountCsvRow acountCsv = new AccountCsvRow();
        acountCsv.account = a;
        try {
            acountCsv.OwnerName = toName(directoryPersonCache.get(a.getAccountOwnerId()));
            acountCsv.OwnerEmail = toEmail(directoryPersonCache.get(a.getAccountOwnerId()));
            acountCsv.CreateUserName = toName(directoryPersonCache.get(a.getCreateUser()));
            acountCsv.CreateUserEmail = toEmail(directoryPersonCache.get(a.getCreateUser()));
            if (a.getLastUpdateUser() != null) {
                acountCsv.UpdateUserName = toName(directoryPersonCache.get(a.getLastUpdateUser()));
                acountCsv.UpdateUserEmail = toEmail(directoryPersonCache.get(a.getLastUpdateUser()));
            }
        } catch (ExecutionException e) {
            logger.error(LOGTAG, e);
        }
        return acountCsv;
    }

    public static AccountCsvRow fromAccount(Account a, LoadingCache<String, DirectoryPerson> directoryPersonCache, String deleteUserId) {
        AccountCsvRow accountCsvRow = fromAccount(a, directoryPersonCache);
        accountCsvRow.DeleteUserId = deleteUserId;
        try {
            DirectoryPerson deletePerson = directoryPersonCache.get(deleteUserId);
            if (deletePerson != null) {
                accountCsvRow.DeleteUserName = deletePerson.getFullName();
                accountCsvRow.DeleteUserEmail = deletePerson.getEmail() == null ? "" : deletePerson.getEmail().getEmailAddress();
                accountCsvRow.DeleteUserId = deletePerson.getKey();
            }
        } catch (ExecutionException e) {
            logger.error(LOGTAG, e);
        }
        accountCsvRow.DeleteDatetime = format.format(new Date());
        return accountCsvRow;
    }
    private static String toName(DirectoryPerson person) {
        if (person == null)
            return "";
        return person.getFullName();
    }
    private static String toEmail(DirectoryPerson person) {
        if (person == null)
            return "";
        return person.getEmail().getEmailAddress();
    }

    private Account account;
    // email simpleDateFormat: Leo Notenboom <leo@somerandomservice.com>
    private String OwnerName = "";
    private String OwnerEmail = "";
    private String CreateUserName = "";
    private String CreateUserEmail = "";
    private String UpdateUserName = "";
    private String UpdateUserEmail = "";
    private String DeleteUserId = "";
    private String DeleteUserName = "";
    private String DeleteUserEmail = "";
    private String DeleteDatetime = "";

    public String[] toStrings() {
        return new String[] { disableNumberFormatting(account.getAccountId()), account.getAccountName(), account.getComplianceClass(),
                account.getPasswordLocation(), account.getAccountOwnerId(), disableNumberFormatting(account.getFinancialAccountNumber()),
                account.getCreateUser(), format.format(account.getCreateDatetime().toCalendar().getTime()),
                account.getLastUpdateUser() == null ? "" : account.getLastUpdateUser(),
                account.getLastUpdateDatetime() == null ? "" : format.format(account.getLastUpdateDatetime().toCalendar().getTime()),
                OwnerName, OwnerEmail, CreateUserName, CreateUserEmail, UpdateUserName, UpdateUserEmail, DeleteUserId, DeleteUserName,
                DeleteUserEmail, DeleteDatetime };
    }
    private static String disableNumberFormatting(String s) {
        return "=\"" + s + "\"";
    }
}

enum TITLE {
    ACCOUNT_ID, ACCOUNT_NAME, COMPLIANCE_CLASS, PASSWORD_LOCATION, ACCOUNT_OWNER_ID, FINANCIAL_ACCOUNT_NUMBER, CREATE_USER, CREATE_DATETIME, LAST_UPDATE_USER, LAST_UPDATE_DATETIME, OWNER_NAME, OWNER_EMAIL, CREATE_USER_NAME, CREATE_USER_EMAIL, UPDATE_USER_NAME, UPDATE_USER_EMAIL, DELETE_USER_ID, DELETE_USER_NAME, DELETE_USER_EMAIL, DELETE_DATETIME;
    public static String[] toStrings() {
        String[] ss = new String[values().length];
        for (int i = 0; i < values().length; i++)
            ss[i] = values()[i].toString();
        return ss;
    }
}
