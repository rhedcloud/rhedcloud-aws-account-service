/*******************************************************************************
  $Source: /cvs/repositories/openii2/openii2/projects/Toolkit-3.0/java/source/com/openii/openeai/toolkit/rdbms/AccountSyncCommand.java,v $
  $Revision: 1.16 $
 *******************************************************************************/

/**********************************************************************
 Copyright (C) 2007 The Board of Trustees of the University of Illinois and Open Integration Incorporated
 */

package edu.emory.awsaccount.service;

//import com.openii.openeai.commands.MessageMetaData;
//import com.openii.openeai.commands.OpeniiSyncCommand;
//import com.openii.openeai.toolkit.ReleaseTag;
//import com.openii.openeai.toolkit.rdbms.persistence.PersistenceException;
//import com.openii.openeai.toolkit.rdbms.persistence.PersistenceHelper;
//import com.openii.openeai.toolkit.rdbms.persistence.hibernate.HibernateMoaPersistenceHelper;
//import com.openii.openeai.toolkit.rdbms.persistence.hibernate.HibernatePersistenceHelper;
//import com.openii.openeai.toolkit.rdbms.persistence.hibernate.MoaInstantiator;

import java.util.ArrayList;
import java.util.Calendar;

import org.apache.log4j.Category;
import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;
import org.openeai.afa.ScheduledCommandException;
import org.openeai.config.AppConfig;
import org.openeai.config.CommandConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.EnterpriseFieldException;
import org.openeai.config.LoggerConfig;
import org.openeai.jms.consumer.commands.*;
import org.openeai.jms.producer.MessageProducer;
import org.openeai.jms.producer.PointToPointProducer;
import org.openeai.jms.producer.ProducerPool;
import org.openeai.jms.producer.PubSubProducer;

import javax.jms.*;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.jdom.*;
import org.jdom.output.XMLOutputter;
import org.openeai.moa.objects.testsuite.TestId;
import org.openeai.transport.RequestService;
import org.openeai.xml.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.MalformedURLException;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;

import org.openeai.dbpool.EnterpriseConnectionPool;
import org.openeai.dbpool.EnterprisePooledConnection;
import org.openeai.moa.ActionableEnterpriseObject;
import org.openeai.moa.EnterpriseObjectQueryException;
import org.openeai.moa.XmlEnterpriseObject;
import org.xml.sax.SAXException;

import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.Account;
import com.amazon.aws.moa.objects.resources.v1_0.AccountQuerySpecification;
import com.amazon.aws.moa.objects.resources.v1_0.Datetime;
import com.amazon.aws.moa.objects.resources.v1_0.SecurityRiskDetectionQuerySpecification;

public class AccountSyncCommand extends SyncCommandImpl implements SyncCommand {
    private static Logger logger = Logger.getLogger(AccountSyncCommand.class);
    private static String LOGTAG = "[AccountSyncCommand] ";
    protected static int requestTimeoutIntervalMilli = -1;
    protected ProducerPool awsAccountServiceRequestProducerPool;
    private boolean _verbose;
    protected ProducerPool _producerPool = null;
    private static final String GENERAL_PROPERTIES = "GeneralProperties";
    public AccountSyncCommand(CommandConfig cConfig) throws InstantiationException, EnterpriseConfigurationObjectException {
        super(cConfig);

        try {
            setProperties(getAppConfig().getProperties(GENERAL_PROPERTIES));
        } catch (Exception e) {
            String msg = "Could not locate the '" + GENERAL_PROPERTIES + "' Property Category.  This command is not configured properly.";
            throw new InstantiationException(msg);
        }

        Properties generalProps = null;
        try {
            generalProps = getAppConfig().getProperties("GeneralProperties");
        } catch (EnterpriseConfigurationObjectException e) {
            logger.error(e);
        }
        if (generalProps == null) {
            generalProps = getProperties();
        }

        try {
            _producerPool = (ProducerPool) getAppConfig().getObject("SyncPublisher");

        } catch (Exception e) {
            logger.warn("No 'SyncPublisher' PubSubProducer found in AppConfig.  "
                    + "Processing will continue but Sync Messages will not be published " + "when changes are made via this Command.");
        }
        awsAccountServiceRequestProducerPool = (ProducerPool) getAppConfig().getObject("AwsAccountServiceRequestProducer");
        // look for an object specific verbose setting and if not found,
        // use the verbose setting from the global GeneralProperties.
        _verbose = new Boolean(generalProps.getProperty("verbose", getProperties().getProperty("verbose", "true"))).booleanValue();

        // MoaInstantiator.setAppConfig(getAppConfig());
        logger.info(ReleaseTag.getReleaseInfo());
        logger.info("AccountSyncCommand, initialized successfully.");
    }

    private void logExecutionComplete() {
        logger.info("[AccountSyncCommand] - execution complete...");
    }

    @SuppressWarnings("unchecked")
    private <T> T getObjectByType(Class<T> t) {
        T moaIn;
        try {
            moaIn = (T) getAppConfig().getObjectByType(t.getName());
        } catch (EnterpriseConfigurationObjectException e) {
            logger.fatal(e);
            throw new RuntimeException(e);
        }
        return moaIn;
    }

    @Override
    public void execute(int messageNumber, Message aMessage) throws CommandException {
        logger.info(LOGTAG + "execution begins...");
        Document inDoc = null;
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

        // get the object from AppConfig...
        Account account = null;
        Account accountBaseline = null;

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
                publishSyncError(inDoc, controleArea,
                        "Exception occurred retrieving and building the '" + msgObject + "' object from AppConfig.", e);
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
        // TODO
        // Query for all Account objects in the AWS Account Service
        // Query the DirectoryService for the full name and email address of the
        // AccountOwner, CreateUser, and LastUpdateUser)
        // Serialize the appropriate Account object fields and DirectoryPerson
        // fields to the prescribed CSV or Excel format
        // Write the file to the file system*
        // Transmit the file
        // PROD.yyyy-mm-dd-hh-mm-ss.csv
        // STAGE.yyyy-mm-dd-hh-mm-ss.csv
        // TEST.yyyy-mm-dd-hh-mm-ss.csv
        // DEV.yyyy-mm-dd-hh-mm-ss.csv
    }
    protected List<Account> queryAllAccounts()
            throws EnterpriseConfigurationObjectException, EnterpriseObjectQueryException, EnterpriseFieldException {
        Account srd = (Account) getAppConfig().getObjectByType(Account.class.getName());
        AccountQuerySpecification securityRiskDetectionQuerySpecification = (AccountQuerySpecification) getAppConfig()
                .getObjectByType(AccountQuerySpecification.class.getName());
        MessageProducer messageProducer = getRequestServiceMessageProducer(awsAccountServiceRequestProducerPool);
        List<Account> accounts = new ArrayList<>();
        try {
            accounts = srd.query(securityRiskDetectionQuerySpecification, (RequestService) messageProducer);
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

    private void publishSyncError(Document inDoc, Element controleArea, String errMessage, Throwable e) {
        logger.fatal(errMessage);
        ArrayList errors = logErrors("MSG-1001", errMessage, inDoc);
        publishSyncError(controleArea, errors, e);
        logExecutionComplete();
    }

    private void rollback(java.sql.Connection conn) {
        logger.info("Rolling back transaction because of some error...");
        try {
            if (conn != null) {
                conn.rollback();
            }
        } catch (Exception e1) {
        }
    }

    private XmlEnterpriseObject retrieveAndBuildObject(String comment, String msgObject, String msgObjectName, Element eData)
            throws CommandException {
        XmlEnterpriseObject xeo = null;
        logger.info("msgObject=" + msgObject + ", msgObjectName=" + msgObjectName);

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

        logger.info("buildObjectFromInput()...");
        try {
            xeo.buildObjectFromInput(eData);
            if (comment != null) {
                if (_verbose) {
                    logger.info(comment + " Object is: " + xeo.toXmlString());
                }
            } else {
                if (_verbose) {
                    logger.info("Object is: " + xeo.toXmlString());
                }
            }
        } catch (Throwable e) {
            logger.error(e);
            throw new CommandException(e.getMessage(), e);
        }
        return xeo;
    }

    private final ArrayList logErrors(String errNumber, String errMessage, Throwable e, Document inDoc) {
        logger.fatal(errMessage, e);
        logger.fatal("Message sent in is: \n" + getMessageBody(inDoc));
        ArrayList errors = new ArrayList();
        errors.add(buildError("application", errNumber, errMessage));
        return errors;
    }

    private final ArrayList logErrors(String errNumber, String errMessage, Document inDoc) {
        logger.fatal(errMessage);
        logger.fatal("Message sent in is: \n" + getMessageBody(inDoc));
        ArrayList errors = new ArrayList();
        errors.add(buildError("application", errNumber, errMessage));
        return errors;
    }
}
