/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2018 Emory University. All rights reserved. 
 ******************************************************************************/

package edu.emory.awsaccount.service;

import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;

import javax.jms.JMSException;
import javax.jms.Message;
//import javax.jms.MessageProducer;
import javax.jms.TextMessage;

import org.apache.commons.validator.GenericValidator;
import org.apache.commons.validator.routines.InetAddressValidator;
import org.apache.log4j.PropertyConfigurator;
import org.jdom.Document;
import org.jdom.Element;
// OpenEAI foundation components
import org.openeai.config.CommandConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.LoggerConfig;
import org.openeai.config.PropertyConfig;
import org.openeai.jms.consumer.commands.CommandException;
import org.openeai.jms.consumer.commands.GenericCrudRequestCommand;
import org.openeai.jms.consumer.commands.RequestCommand;
import org.openeai.jms.producer.MessageProducer;
import org.openeai.jms.producer.ProducerPool;
import org.openeai.layouts.EnterpriseLayoutException;
import org.openeai.moa.EnterpriseObjectSyncException;
import org.openeai.moa.objects.resources.Authentication;
import org.openeai.moa.objects.testsuite.TestId;
import org.openeai.transport.SyncService;

import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.AccountProvisioningAuthorization;
import com.amazon.aws.moa.objects.resources.v1_0.AccountAliasQuerySpecification;
import com.amazon.aws.moa.objects.resources.v1_0.AccountProvisioningAuthorizationQuerySpecification;
import edu.emory.awsaccount.service.provider.AccountProvisioningAuthorizationProvider;
import edu.emory.awsaccount.service.provider.ProviderException;

/**
 * This command handles requests for AccountProvisioningAuthorization objects.
 * Specifically, it handles a Query-Request
 * 
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 13 August 2018
 * 
 */

public class AccountProvisioningAuthorizationRequestCommand extends AwsAccountRequestCommand implements RequestCommand {
    private static String LOGTAG = "[AccountProvisioningAuthorizationRequestCommand] ";
    private AccountProvisioningAuthorizationProvider m_provider;
    private ProducerPool m_producerPool;
    public AccountProvisioningAuthorizationRequestCommand(CommandConfig cConfig) throws InstantiationException {
        super(cConfig);

        logger.info(LOGTAG + "Initializing " + ReleaseTag.getReleaseInfo());

        // Initialize a command-specific logger if it exists.
        try {
            LoggerConfig lConfig = new LoggerConfig();
            lConfig = (LoggerConfig) getAppConfig().getObjectByType(lConfig.getClass().getName());
            PropertyConfigurator.configure(lConfig.getProperties());
        } catch (Exception e) {
        }

        // Set the properties for this command.
        try {
            PropertyConfig pConfig = (PropertyConfig) getAppConfig().getObject("GeneralProperties");
            setProperties(pConfig.getProperties());
        } catch (EnterpriseConfigurationObjectException ecoe) {
            // An error occurred retrieving a property config from AppConfig.
            // Log it
            // and throw an exception.
            String errMsg = "An error occurred retrieving a property config from " + "AppConfig. The exception is: " + ecoe.getMessage();
            logger.fatal(LOGTAG + errMsg);
            throw new InstantiationException(errMsg);
        }

        // Initialize an AccountProvisioningAuthorizationProvider
        String className = getProperties().getProperty("accountProvisioningAuthorizationProviderClassName");
        if (className == null || className.equals("")) {
            String errMsg = "No accountProvisioningAuthorizationProviderClassName property " 
            	+ "specified. Can't continue.";
            logger.fatal(LOGTAG + errMsg);
            throw new InstantiationException(errMsg);
        }
        logger.info(LOGTAG + "accountProvisioningAuthorizationProviderClassName is: " + className);

        AccountProvisioningAuthorizationProvider provider = null;
        try {
            logger.info(LOGTAG + "Getting class for name: " + className);
            Class providerClass = Class.forName(className);
            if (providerClass == null)
                logger.info(LOGTAG + "providerClass is null.");
            else
                logger.info(LOGTAG + "providerClass is not null.");
            provider = (AccountProvisioningAuthorizationProvider) Class.forName(className).newInstance();
            logger.info(LOGTAG + "Initializing AccountProvisioningAuthorizationProvider: " + provider.getClass().getName());
            provider.init(getAppConfig());
            logger.info(LOGTAG + "AccountProvisioningAuthorizationProvider initialized.");
            setProvider(provider);
        } catch (ClassNotFoundException cnfe) {
            String errMsg = "Class named " + className + "not found on the " + "classpath.  The exception is: " + cnfe.getMessage();
            logger.fatal(LOGTAG + errMsg);
            throw new InstantiationException(errMsg);
        } catch (IllegalAccessException iae) {
            String errMsg = "An error occurred getting a class for name: " + className + ". The exception is: " + iae.getMessage();
            logger.fatal(LOGTAG + errMsg);
            throw new InstantiationException(errMsg);
        } catch (ProviderException pe) {
            String errMsg = "An error occurred initializing the " + "AccountProvisioningAuthorizationProvider " + className + ". The exception is: "
                    + pe.getMessage();
            logger.fatal(LOGTAG + errMsg);
            throw new InstantiationException(errMsg);
        }

        // Verify that we have all required objects in the AppConfig.
        // Get a configured AccountProvisioningAuthorization from AppConfig.
        AccountProvisioningAuthorization apa = new AccountProvisioningAuthorization();
        try {
            apa = (AccountProvisioningAuthorization) getAppConfig().getObjectByType(apa.getClass().getName());
        } catch (EnterpriseConfigurationObjectException eoce) {
            String errMsg = "Error retrieving an object from AppConfig: The exception" + "is: " + eoce.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new InstantiationException(errMsg);
        }

        // Get a AccountProvisioningAuthorizationQuerySpecification from AppConfig.
        AccountProvisioningAuthorizationQuerySpecification querySpec = 
        	new AccountProvisioningAuthorizationQuerySpecification();
        try {
            querySpec = (AccountProvisioningAuthorizationQuerySpecification) 
            	getAppConfig().getObjectByType(querySpec.getClass().getName());
        } catch (EnterpriseConfigurationObjectException eoce) {
            String errMsg = "Error retrieving an object from AppConfig: " + 
            	"The exception" + "is: " + eoce.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new InstantiationException(errMsg);
        }

        logger.info(LOGTAG + "Instantiated successfully.");
    }

    /**
     * @param int,
     *            the number of the message processed by the consumer
     * @param Message
     *            , the message for the command to process
     * @throws CommandException
     *             , with details of the error processing the message
     *             <P>
     *             This method makes a local copy of the response and provide
     *             documents to use in the reply to the request. Then it
     *             converts the JMS message to an XML document, retrieves the
     *             text portion of the message, clears the message body in
     *             preparation for the reply, gets the ControlArea from the XML
     *             document, and verifies that message object of the message is
     *             a VirtualPrivateCloud and the action is a query, generate,
     *             update, or delete. Then this method uses the configured
     *             AccountAliasProvider to perform each operation.
     */
    @Override
    public final Message execute(int messageNumber, Message aMessage) throws CommandException {
        // Get the execution start time.
        long startTime = System.currentTimeMillis();

        // Make a local copy of the response documents to use in the replies.
        Document localResponseDoc = (Document) getResponseDocument().clone();
        Document localProvideDoc = (Document) getProvideDocument().clone();

        // Convert the JMS Message to an XML Document
        Document inDoc = null;
        try {
            inDoc = initializeInput(messageNumber, aMessage);
        } catch (Exception e) {
            String errMsg = "Exception occurred processing input message in " 
            		+ "org.openeai.jms.consumer.commands.Command.  Exception: "
                    + e.getMessage();
            throw new CommandException(errMsg);
        }

        // If verbose, write the message body to the log.
        if (getVerbose())
            logger.info("Message sent in is: \n" + getMessageBody(inDoc));

        // Retrieve text portion of message.
        TextMessage msg = (TextMessage) aMessage;
        try {
            // Clear the message body for the reply, so we do not
            // have to do it later.
            msg.clearBody();
        } catch (JMSException jmse) {
            String errMsg = "Error clearing the message body.";
            throw new CommandException(errMsg + ". The exception is: " 
            		+ jmse.getMessage());
        }

        // Get the ControlArea from XML document.
        Element eControlArea = getControlArea(inDoc.getRootElement());

        // Get messageAction and messageObject attributes from the
        // ControlArea element.
        String msgAction = eControlArea.getAttribute("messageAction").getValue();
        String msgObject = eControlArea.getAttribute("messageObject").getValue();

        // Verify that the message object we are dealing with is a
        // VirtualPrivateCloud object; if not, reply with an error.
        if (msgObject.equalsIgnoreCase("AccountProvisioningAuthorization") == false) {
            String errType = "application";
            String errCode = "OpenEAI-1001";
            String errDesc = "Unsupported message object: " + msgObject + 
            		". This command expects 'AccountProvisioningAuthorization'.";
            logger.error(LOGTAG + errDesc);
            logger.error(LOGTAG + "Message sent in is: \n" + getMessageBody(inDoc));
            ArrayList errors = new ArrayList();
            errors.add(buildError(errType, errCode, errDesc));
            String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
            return getMessage(msg, replyContents);
        }

        // Get the senderApplicationId and the authUserId
        Element eSenderAppId = eControlArea.getChild("Sender").getChild("MessageId").getChild("SenderAppId");
        String senderAppId = eSenderAppId.getValue();
        Element eAuthUserId = eControlArea.getChild("Sender").getChild("Authentication").getChild("AuthUserId");
        ;
        String authUserId = eAuthUserId.getValue();

        // Temporary workaround for test suite app
        if (authUserId.equalsIgnoreCase("TestSuiteApplication")) {
            authUserId = "testsuiteapp@emory.edu/127.0.0.1";
        }

        // Validate the format of the AuthUserId. If the format is invalid,
        // respond with an error.
        if (validateAuthUserId(authUserId) == false) {
            String errType = "application";
            String errCode = "AwsAccountService-1001";
            String errDesc = "Invalid AuthUserId. The value '" + authUserId
                    + "' is not valid. The expected format is user@domain/ip number.";
            logger.fatal(LOGTAG + errDesc);
            logger.fatal(LOGTAG + "Message sent in is: \n" + getMessageBody(inDoc));
            ArrayList errors = new ArrayList();
            errors.add(buildError(errType, errCode, errDesc));
            String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
            return getMessage(msg, replyContents);
        }

        // Get the IP number from the AuthUserId.
        String ipNumber = getIpNumberFromAuthUserId(authUserId);

        // Get the EPPN from from AuthUserId.
        String eppn = getEppnFromAuthUserId(authUserId);

        // Get a configured AccountAlias from AppConfig.
        AccountProvisioningAuthorization apa = new AccountProvisioningAuthorization();
        TestId testId = new TestId();
        try {
            apa = (AccountProvisioningAuthorization) getAppConfig().getObjectByType(apa.getClass().getName());
            testId = (TestId) getAppConfig().getObjectByType(testId.getClass().getName());
        } catch (EnterpriseConfigurationObjectException eoce) {
            String errMsg = "Error retrieving an object from AppConfig: The exception" + "is: " + eoce.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new CommandException(errMsg, eoce);
        }
        Element eTestId = inDoc.getRootElement().getChild("ControlAreaRequest").getChild("Sender").getChild("TestId");

        // Handle a Query-Request.
        if (msgAction.equalsIgnoreCase("Query")) {
            logger.info(LOGTAG + "Handling an com.amazon.aws.Provisioning.AccountProvisioningAuthorization." + "Query-Request message.");
            Element eQuerySpec = inDoc.getRootElement().getChild("DataArea").getChild("AccountAliasQuerySpecification");

            // Get a configured query object from AppConfig.
            AccountProvisioningAuthorizationQuerySpecification querySpec = new AccountProvisioningAuthorizationQuerySpecification();
            try {
                querySpec = (AccountProvisioningAuthorizationQuerySpecification) getAppConfig().getObjectByType(querySpec.getClass().getName());
            } catch (EnterpriseConfigurationObjectException eoce) {
                String errMsg = "Error retrieving an object from AppConfig: " + "The exception" + "is: " + eoce.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new CommandException(errMsg);
            }

            // If the query object is null, return and error.
            if (eQuerySpec != null) {
                try {
                    querySpec.buildObjectFromInput(eQuerySpec);
                } catch (EnterpriseLayoutException ele) {
                    // There was an error building the query object from a query
                    // element.
                    String errType = "application";
                    String errCode = "AwsAccontService-9004";
                    String errDesc = "An error occurred building the query " + "object from the DataArea element in the "
                            + "Query-Request message. The exception " + "is: " + ele.getMessage();
                    logger.error(LOGTAG + errDesc);
                    logger.error("Message sent in is: \n" + getMessageBody(inDoc));
                    ArrayList errors = new ArrayList();
                    errors.add(buildError(errType, errCode, errDesc));
                    String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
                    return getMessage(msg, replyContents);
                }
            } else {
                // The query spec is null.
                String errType = "application";
                String errCode = "AwsAccontService-9005";
                String errDesc = "An error occurred building the query " + "object from the DataArea element in the "
                        + "Query-Request message. The query spec is null.";
                logger.error(LOGTAG + errDesc);
                logger.error("Message sent in is: \n" + getMessageBody(inDoc));
                ArrayList errors = new ArrayList();
                errors.add(buildError(errType, errCode, errDesc));
                String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
                return getMessage(msg, replyContents);
            }

            // Query for the AccountProvisioningAuthorization using the provider.
            logger.info(LOGTAG + "Querying for the AccountProvisioningAuthorization...");

            List results = null;
            try {
                long queryStartTime = System.currentTimeMillis();
                results = getProvider().query(querySpec);
                long queryTime = System.currentTimeMillis() - queryStartTime;
                logger.info(LOGTAG + "Queried for AccountProvisioningAuthorization in "
                		+ queryTime + "ms.");
            } catch (ProviderException pe) {
                // There was an error generating the identity
                String errType = "application";
                String errCode = "AwsAccountService-2006";
                String errDesc = "An error occurred querying for the AccountProvisioningAuthorization." 
                		+ "The " + "exception is: " + pe.getMessage();
                logger.error(LOGTAG + errDesc);
                logger.error("Message sent in is: \n" + getMessageBody(inDoc));
                ArrayList errors = new ArrayList();
                errors.add(buildError(errType, errCode, errDesc));
                String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
                return getMessage(msg, replyContents);
            }

            if (results != null) {
                logger.info(LOGTAG + "Found " + results.size() + " matching result(s).");
            } else {
                logger.info(LOGTAG + "Results are null; no matching " +
                	"AccountProvisioningAuthorization found.");
            }

            // Prepare the response.
            localProvideDoc.getRootElement().getChild("DataArea").removeContent();
            // If there are results, place them in the response.
            if (results != null && results.size() > 0) {

                ArrayList authorizationList = new ArrayList();
                for (int i = 0; i < results.size(); i++) {
                    Element eAuthorization = null;
                    try {
                        AccountProvisioningAuthorization authorization = 
                        	(AccountProvisioningAuthorization) results.get(i);
                        eAuthorization = (Element) authorization.buildOutputFromObject();
                        authorizationList.add(eAuthorization);
                        if (eTestId != null)
                            authorization.setTestId(testId);
                    } catch (EnterpriseLayoutException ele) {
                        String errMsg = "An error occurred serializing " +
                                "AccountProvisioningAuthorization object " +
                        		"to an XML element. The exception is: " + 
                                ele.getMessage();
                        logger.error(LOGTAG + errMsg);
                        throw new CommandException(errMsg, ele);
                    }
                }
                localProvideDoc.getRootElement().getChild("DataArea").addContent(authorizationList);
            }
            String replyContents = buildReplyDocument(eControlArea, localProvideDoc);

            // Return the response with status success.
            return getMessage(msg, replyContents);
        }

        else {
            // The messageAction is invalid; it is not a query, generate,
            // create. update, or delete
            String errType = "application";
            String errCode = "OpenEAI-1002";
            String errDesc = "Unsupported message action: " + msgAction + ". "
                    + "This command only supports query	.";
            logger.fatal(LOGTAG + errDesc);
            logger.fatal("Message sent in is: \n" + getMessageBody(inDoc));
            ArrayList errors = new ArrayList();
            errors.add(buildError(errType, errCode, errDesc));
            String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
            return getMessage(msg, replyContents);
        }
    }

    /**
     * @param AccountProvisioningAuthorizationProvider, the provider
     * <P>
     * Sets the AccountProvisioningAuthorizationProvider for this command.
     */
    protected void setProvider(AccountProvisioningAuthorizationProvider provider) {
        m_provider = provider;
    }

    /**
     * @return AccountProvisioningAuthorizationProvider, the provider
     * <P>
     * Gets the provider for this command.
     */
    protected AccountProvisioningAuthorizationProvider getProvider() {
        return m_provider;
    }

    /**
     * @param ProducerPool
     *            , the producer pool for this command.
     *            <P>
     *            Sets the producer pool for this command.
     */
    protected void setProducerPool(ProducerPool producerPool) {
        m_producerPool = producerPool;
    }

    /**
     * @return ProducerPool, the producer pool for this command.
     *         <P>
     *         Gets the producer pool for this command.
     */
    protected ProducerPool getProducerPool() {
        return m_producerPool;
    }

    /**
     * @return String, a String containing the EPPN.
     *         <P>
     *         Parses the EPPN from the AuthUserId and returns it.
     */
    private String getEppnFromAuthUserId(String authUserId) {
        StringTokenizer st = new StringTokenizer(authUserId, "/");
        String eppn = st.nextToken();
        return eppn;
    }

    /**
     * @return String, a String containing the IP number.
     *         <P>
     *         Parses the EPPN from the AuthUserId and returns it.
     */
    private String getIpNumberFromAuthUserId(String authUserId) {
        StringTokenizer st = new StringTokenizer(authUserId, "/");
        st.nextToken();
        String ipNumber = st.nextToken();
        return ipNumber;
    }

    /**
     * @return boolean, a flag indicating whether or not the authUserId id is
     *         valid.
     *         <P>
     *         Validates the format of the AuthUserId to be eppn/ipNumber. More
     *         specifically, this is user@domain/ipnumber.
     */
    protected static boolean validateAuthUserId(String authUserId) {
        StringTokenizer st = new StringTokenizer(authUserId, "/");

        // If there are less than two tokens return false.
        if (st.countTokens() < 2) {
            logger.error(LOGTAG + "AuthUserId does not consist of two tokens.");
            return false;
        }

        // Validate the EPPN with an e-mail address validator.
        String eppn = st.nextToken();
        GenericValidator gv = new GenericValidator();
        if (gv.isEmail(eppn) == false) {
            logger.error(LOGTAG + "EPPN is not a valid e-mail: " + eppn);
            return false;
        }

        // Validate the IP number.
        String ip = st.nextToken();
        InetAddressValidator iav = new InetAddressValidator();
        if (iav.isValid(ip) == false) {
            logger.info(LOGTAG + "IP number is not valid: " + ip);
            return false;
        }

        // If all validation checks have passed, return true.
        return true;
    }

}
