/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2018 Emory University. All rights reserved. 
 ******************************************************************************/

package edu.emory.awsaccount.service;

// Core Java
import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;

// Java Message Service
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.TextMessage;

// Log4j
import org.apache.log4j.*;

// JDOM
import org.jdom.Document;
import org.jdom.Element;

// OpenEAI foundation components
import org.openeai.config.CommandConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.EnterpriseFieldException;
import org.openeai.config.LoggerConfig;
import org.openeai.config.PropertyConfig;
import org.openeai.jms.consumer.commands.CommandException;
import org.openeai.jms.consumer.commands.RequestCommand;
import org.openeai.jms.producer.MessageProducer;
import org.openeai.jms.producer.ProducerPool;
import org.openeai.layouts.EnterpriseLayoutException;
import org.openeai.moa.EnterpriseObjectSyncException;
import org.openeai.moa.XmlEnterpriseObjectException;
import org.openeai.moa.objects.resources.Authentication;
import org.openeai.moa.objects.testsuite.TestId;
import org.openeai.transport.SyncService;

import com.amazon.aws.moa.objects.resources.v1_0.AccountAliasQuerySpecification;
// AWS MOA objects
import com.amazon.aws.moa.objects.resources.v1_0.StackQuerySpecification;
import com.amazon.aws.moa.objects.resources.v1_0.StackRequisition;
import com.amazon.aws.moa.jmsobjects.cloudformation.v1_0.Stack;
import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.AccountAlias;

// VPC Provider Implementation
import edu.emory.awsaccount.service.provider.StackProvider;
import edu.emory.awsaccount.service.provider.AccountAliasProvider;
import edu.emory.awsaccount.service.provider.ProviderException;

//Apache Commons Validators
import org.apache.commons.validator.GenericValidator;
import org.apache.commons.validator.routines.InetAddressValidator;

/**
 * This command handles requests for AccountAlias objects.
 * Specifically, it handles a Query-Request, a Create-Request, 
 * 
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 12 July 2018
 * 
 */

public class AccountAliasRequestCommand extends AwsAccountRequestCommand implements RequestCommand {
    private static String LOGTAG = "[AccountAliasRequestCommand] ";
    private static Logger logger = Logger.getLogger(StackRequestCommand.class);
    private AccountAliasProvider m_provider = null;
    private ProducerPool m_producerPool = null;

    /**
     * @param CommandConfig
     * @throws InstantiationException
     *             <P>
     *             This constructor initializes the command using a
     *             CommandConfig object. It invokes the constructor of the
     *             ancestor, RequestCommandImpl, and then retrieves one
     *             PropertyConfig object from AppConfig by name and gets and
     *             sets the command properties using that PropertyConfig object.
     *             This means that this command must have one PropertyConfig
     *             object in its configuration named 'GeneralProperties'. This
     *             constructor also initializes the response document and
     *             provide document used in replies.
     */
    public AccountAliasRequestCommand(CommandConfig cConfig) throws InstantiationException {
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
            String errMsg = "An error occurred retrieving a property config from " + "AppConfig. The exception is: "
                    + ecoe.getMessage();
            logger.fatal(LOGTAG + errMsg);
            throw new InstantiationException(errMsg);
        }

        // Initialize an AccountAliasProvider
        String className = getProperties().getProperty("accountAliasProviderClassName");
        if (className == null || className.equals("")) {
            String errMsg = "No accountAliasProviderClassName property "
                    + "specified. Can't continue.";
            logger.fatal(LOGTAG + errMsg);
            throw new InstantiationException(errMsg);
        }
        logger.info(LOGTAG + "accountAliasClassName is: " + className);

        AccountAliasProvider provider = null;
        try {
            logger.info(LOGTAG + "Getting class for name: " + className);
            Class providerClass = Class.forName(className);
            if (providerClass == null)
                logger.info(LOGTAG + "providerClass is null.");
            else
                logger.info(LOGTAG + "providerClass is not null.");
            provider = (AccountAliasProvider) Class.forName(className).newInstance();
            logger.info(LOGTAG + "Initializing AccountAliasProvider: "
                    + provider.getClass().getName());
            provider.init(getAppConfig());
            logger.info(LOGTAG + "AccountAliasProvider initialized.");
            setProvider(provider);
        } catch (ClassNotFoundException cnfe) {
            String errMsg = "Class named " + className + "not found on the " + "classpath.  The exception is: "
                    + cnfe.getMessage();
            logger.fatal(LOGTAG + errMsg);
            throw new InstantiationException(errMsg);
        } catch (IllegalAccessException iae) {
            String errMsg = "An error occurred getting a class for name: " + className + ". The exception is: "
                    + iae.getMessage();
            logger.fatal(LOGTAG + errMsg);
            throw new InstantiationException(errMsg);
        } catch (ProviderException pe) {
            String errMsg = "An error occurred initializing the " + "AccountAliasProvider " + className
                    + ". The exception is: " + pe.getMessage();
            logger.fatal(LOGTAG + errMsg);
            throw new InstantiationException(errMsg);
        }

        // Get a SyncService to use to publish sync messages.
        try {
            ProducerPool pool = (ProducerPool) getAppConfig().getObject("SyncPublisher");
            setProducerPool(pool);
        } catch (EnterpriseConfigurationObjectException eoce) {
            String errMsg = "Error retrieving a ProducerPool object " + "from AppConfig. The exception is: "
                    + eoce.getMessage();
            logger.fatal(LOGTAG + errMsg);
            throw new InstantiationException(errMsg);
        }
      
        // Verify that we have all required objects in the AppConfig.
        // Get a configured AccountAlias from AppConfig.
        AccountAlias alias = new AccountAlias();
        try {
            alias = (AccountAlias) getAppConfig()
            	.getObjectByType(alias.getClass().getName());
        } catch (EnterpriseConfigurationObjectException eoce) {
            String errMsg = "Error retrieving an object from AppConfig: The exception" + "is: " + eoce.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new InstantiationException(errMsg);
        }

        // Get a AccountAliasQuerySpecification from AppConfig.
        AccountAliasQuerySpecification querySpec = new AccountAliasQuerySpecification();
        try {
            querySpec = (AccountAliasQuerySpecification) getAppConfig()
            	.getObjectByType(querySpec.getClass().getName());
        } catch (EnterpriseConfigurationObjectException eoce) {
            String errMsg = "Error retrieving an object from AppConfig: " +
            	"The exception" + "is: " + eoce.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new InstantiationException(errMsg);
        }

        logger.info(LOGTAG + "instantiated successfully.");
    }

    /**
     * @param int, the number of the message processed by the consumer
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
     *             a VirtualPrivateCloud and the action is a query,
     *             generate, update, or delete. Then this method uses the 
     *             configured StackProvider to perform each
     *             operation.
     */
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
                    + "org.openeai.jms.consumer.commands.Command.  Exception: " + e.getMessage();
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
            throw new CommandException(errMsg + ". The exception is: " + jmse.getMessage());
        }

        // Get the ControlArea from XML document.
        Element eControlArea = getControlArea(inDoc.getRootElement());

        // Get messageAction and messageObject attributes from the
        // ControlArea element.
        String msgAction = eControlArea.getAttribute("messageAction").getValue();
        String msgObject = eControlArea.getAttribute("messageObject").getValue();

        // Verify that the message object we are dealing with is a
        // VirtualPrivateCloud object; if not, reply with an error.
        if (msgObject.equalsIgnoreCase("AccountAlias") == false) {
            String errType = "application";
            String errCode = "OpenEAI-1001";
            String errDesc = "Unsupported message object: " + msgObject
                    + ". This command expects 'AccountAlias'.";
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
        AccountAlias alias = new AccountAlias();
        TestId testId = new TestId();
        try {
            alias = (AccountAlias) getAppConfig().getObjectByType(alias.getClass().getName());
            testId = (TestId) getAppConfig().getObjectByType(testId.getClass().getName());
        } catch (EnterpriseConfigurationObjectException eoce) {
            String errMsg = "Error retrieving an object from AppConfig: The exception" + "is: " + eoce.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new CommandException(errMsg, eoce);
        }
        Element eTestId = inDoc.getRootElement().getChild("ControlAreaRequest").getChild("Sender").getChild("TestId");

        // Handle a Create-Request.
        if (msgAction.equalsIgnoreCase("Create")) {
            logger.info(LOGTAG + "Handling a com.amazon.aws.Provisioning.AccountAlias.Create-Request"
                    + " message.");
            Element eCreateObject = inDoc.getRootElement().getChild("DataArea").getChild("AccountAlias");

            // Verify that the object element is not null. If it is
            // null, reply
            // with an error.
            if (eCreateObject == null) {
                String errType = "application";
                String errCode = "OpenEAI-1015";
                String errDesc = "Invalid element found in the Create-Request "
                        + "message. This command expects a AccountAlias";
                logger.error(LOGTAG + errDesc);
                logger.error("Message sent in is: \n" + getMessageBody(inDoc));
                ArrayList errors = new ArrayList();
                errors.add(buildError(errType, errCode, errDesc));
                String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
                return getMessage(msg, replyContents);
            }

            // Now build an AccountAlias object from the element in the message.
            try {
                alias.buildObjectFromInput(eCreateObject);
                if (eTestId != null)
                    testId.buildObjectFromInput(eTestId);
            } catch (EnterpriseLayoutException ele) {
                // There was an error building the query object from a query
                // element.
                String errType = "application";
                String errCode = "AwsAccountService-2001";
                String errDesc = "An error occurred building the object from "
                        + "the DataArea element in the Create-Request message."
                		+ "The exception " + "is: " + ele.getMessage();
                logger.error(LOGTAG + errDesc);
                logger.error("Message sent in is: \n" + getMessageBody(inDoc));
                ArrayList errors = new ArrayList();
                errors.add(buildError(errType, errCode, errDesc));
                String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
                return getMessage(msg, replyContents);
            }

            // Create the AccountAlias object using the provider implementation.
            logger.info(LOGTAG + "Creating an Stack...");

            try {
            	long generateStartTime = System.currentTimeMillis();
                getProvider().create(alias);
                long generateTime = System.currentTimeMillis() - generateStartTime;
                logger.info(LOGTAG + "Generate Stack in " + generateTime + " ms." );
                if (eTestId != null)
                    alias.setTestId(testId);
            } catch (Throwable pe) {
                logger.error(LOGTAG, pe);
                // There was an error generating the identity
                String errType = "application";
                String errCode = "AwsAccountService-2003";
                String errDesc = "An error occurred creating the AccountAlias. The "
                		+ "exception is: " + pe.getMessage();
                logger.error(LOGTAG + errDesc);
                logger.error("Message sent in is: \n" + getMessageBody(inDoc));
                ArrayList errors = new ArrayList();
                errors.add(buildError(errType, errCode, errDesc));
                String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
                return getMessage(msg, replyContents);
            }
            logger.info(LOGTAG + "Created AccountAlias: " + alias.toString());

            logger.info(LOGTAG + "Publishing sync... " );
            // Publish a Create-Sync Message
            try {
                MessageProducer producer = getProducerPool().getProducer();
                if (getVerbose())
                    logger.info(LOGTAG + "Publishing AccountAlias.Create-Sync message...");
                Authentication auth = new Authentication();
                auth.setAuthUserId(authUserId);
                auth.setAuthUserSignature("none");
                alias.setAuthentication(auth);
                alias.createSync((SyncService) producer);
                logger.info(LOGTAG + "Published AccountAlias.Create-Sync" + " message.");
            } catch (EnterpriseObjectSyncException eose) {
                String errMsg = "An error occurred publishing the AccountAlias.Create-Sync"
                        + " message after generating a Stack. The " + "exception is: "
                        + eose.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new CommandException(errMsg, eose);
            } catch (JMSException jmse) {
                String errMsg = "An error occurred publishing the AccountAlias.Create-Sync"
                        + " message after creating the AccountAlias. The " + "exception is: "
                        + jmse.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new CommandException(errMsg, jmse);
            }
            
            logger.info(LOGTAG + "Prepare response... " );          
            // Prepare the response.
            if (localResponseDoc.getRootElement().getChild("DataArea") != null) {
            	localResponseDoc.getRootElement().getChild("DataArea").removeContent();
            }
            else {
            	localResponseDoc.getRootElement().addContent(new Element("DataArea"));
            }
            Element eAccountAlias = null;
            try {
                eAccountAlias = (Element) alias.buildOutputFromObject();
            } catch (EnterpriseLayoutException ele) {
                String errMsg = "An error occurred serializing an AccountAlias object to an XML element. The exception is: "
                        + ele.getMessage();
                logger.error(LOGTAG + errMsg, ele);
                throw new CommandException(errMsg, ele);
            }
            localResponseDoc.getRootElement().getChild("DataArea").addContent(eAccountAlias);
            String replyContents = buildReplyDocument(eControlArea, localResponseDoc);

            // Log execution time.
            long executionTime = System.currentTimeMillis() - startTime;
            logger.info(LOGTAG + "Create-Request command execution complete in " + executionTime + " ms.");

            // Return the response with status success.
            return getMessage(msg, replyContents);
        }
        
        // Handle a Query-Request.
        if (msgAction.equalsIgnoreCase("Query")) {
            logger.info(LOGTAG + "Handling an com.amazon.aws.Provisioning.AccountAlias."
                    + "Query-Request message.");
            Element eQuerySpec = inDoc.getRootElement().getChild("DataArea")
                    .getChild("AccountAliasQuerySpecification");

            // Get a configured query object from AppConfig.            
            AccountAliasQuerySpecification querySpec = new AccountAliasQuerySpecification();
            try {
                querySpec = (AccountAliasQuerySpecification) getAppConfig().getObjectByType(
                        querySpec.getClass().getName());
            } catch (EnterpriseConfigurationObjectException eoce) {
                String errMsg = "Error retrieving an object from AppConfig: " +
                	"The exception" + "is: " + eoce.getMessage();
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
                    String errCode = "AwsAccontService-2004";
                    String errDesc = "An error occurred building the query " 
                    		+ "object from the DataArea element in the " 
                    		+ "Query-Request message. The exception " + "is: "
                            + ele.getMessage();
                    logger.error(LOGTAG + errDesc);
                    logger.error("Message sent in is: \n" + getMessageBody(inDoc));
                    ArrayList errors = new ArrayList();
                    errors.add(buildError(errType, errCode, errDesc));
                    String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
                    return getMessage(msg, replyContents);
                }
            }
            else {
                // The query spec is null.
                String errType = "application";
                String errCode = "AwsAccontService-2005";
                String errDesc = "An error occurred building the query " 
                		+ "object from the DataArea element in the " 
                		+ "Query-Request message. The query spec is null.";
                logger.error(LOGTAG + errDesc);
                logger.error("Message sent in is: \n" + getMessageBody(inDoc));
                ArrayList errors = new ArrayList();
                errors.add(buildError(errType, errCode, errDesc));
                String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
                return getMessage(msg, replyContents);  
            }           

            // Query for the Stack from the provider.
            logger.info(LOGTAG + "Querying for the AccountAlias...");

            List results = null;
            try {
            	long queryStartTime = System.currentTimeMillis();
                results = getProvider().query(querySpec);
                long queryTime = System.currentTimeMillis() - queryStartTime;
                logger.info(LOGTAG + "Queried for AccountAlias in " + queryTime + "ms.");
            } catch (ProviderException pe) {
                // There was an error generating the identity
                String errType = "application";
                String errCode = "AwsAccountService-2006";
                String errDesc = "An error occurred querying for the Stack." 
                		+ "The " + "exception is: "
                        + pe.getMessage();
                logger.error(LOGTAG + errDesc);
                logger.error("Message sent in is: \n" + getMessageBody(inDoc));
                ArrayList errors = new ArrayList();
                errors.add(buildError(errType, errCode, errDesc));
                String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
                return getMessage(msg, replyContents);
            }
            
            if (results != null) {
            	logger.info(LOGTAG + "Found " + results.size() + " matching result(s)."); 
            }
            else {
            	logger.info(LOGTAG + "Results are null; no matching AccountAlias found.");
            }

            // Prepare the response.
            localProvideDoc.getRootElement().getChild("DataArea").removeContent();
            // If there are results, place them in the response.
            if (results != null && results.size() > 0) {
            	
                ArrayList accountAliasList = new ArrayList();
                for (int i = 0; i < results.size(); i++) {
                    Element eAccountAlias = null;
                    try {
                        alias = (AccountAlias)results.get(i);
                        eAccountAlias = (Element) alias.buildOutputFromObject();
                        accountAliasList.add(eAccountAlias);
                        if (eTestId != null)
                            alias.setTestId(testId);
                    } catch (EnterpriseLayoutException ele) {
                        String errMsg = "An error occurred serializing "
                                + "Stack object to an XML element. " 
                        		+ "The exception is: " + ele.getMessage();
                        logger.error(LOGTAG + errMsg);
                        throw new CommandException(errMsg, ele);
                    }
                }
                localProvideDoc.getRootElement().getChild("DataArea").addContent(accountAliasList);
            }
            String replyContents = buildReplyDocument(eControlArea, localProvideDoc);

            // Return the response with status success.
            return getMessage(msg, replyContents);
        }
              
        // Handle a Delete-Request.
        if (msgAction.equalsIgnoreCase("Delete")) {
            logger.info(LOGTAG + "Handling a com.amazon.aws.Provisioning." +
            	"AccountAlias.Delete-Request message.");
            Element eAccountAlias = inDoc.getRootElement().getChild("DataArea")
            	.getChild("DeleteData").getChild("AccountAlias");

            // Verify that the AccountAlias element is not null. If it is
            // null, reply with an error.
            if (eAccountAlias == null) {
                String errType = "application";
                String errCode = "OpenEAI-2015";
                String errDesc = "Invalid element found in the Delete-Request "
                        + "message. This command expects an AccountAlias";
                logger.error(LOGTAG + errDesc);
                logger.error("Message sent in is: \n" + getMessageBody(inDoc));
                ArrayList errors = new ArrayList();
                errors.add(buildError(errType, errCode, errDesc));
                String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
                return getMessage(msg, replyContents);
            }
            
            // Get a configured AccountAlias from AppConfig.
            alias = new AccountAlias();
            try {
                alias = (AccountAlias) getAppConfig()
                	.getObjectByType(alias.getClass().getName());
            } catch (EnterpriseConfigurationObjectException eoce) {
                String errMsg = "Error retrieving an object from AppConfig: The exception" + "is: " + eoce.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new CommandException(errMsg);
            }

            // Now build an AccountAlias object from the element in the message.
            try {
                alias.buildObjectFromInput(eAccountAlias);
                if (eTestId != null) testId.buildObjectFromInput(eTestId);
                alias.setTestId(testId);
            } 
            catch (EnterpriseLayoutException ele) {
                // There was an error building the delete object from the
                // delete element.
                String errType = "application";
                String errCode = "AwsAccountService-2007";
                String errDesc = "An error occurred building the delete object " +
                		"from the DataArea element in the Delete-Request " +
                		"message. The exception " + "is: " + ele.getMessage();
                logger.error(LOGTAG + errDesc);
                logger.error("Message sent in is: \n" + getMessageBody(inDoc));
                ArrayList errors = new ArrayList();
                errors.add(buildError(errType, errCode, errDesc));
                String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
                return getMessage(msg, replyContents);
            }

            // Delete the AccountAlias object using the provider.
            logger.info(LOGTAG + "Deleting an AccountAlias...");

            try {
            	long deleteStartTime = System.currentTimeMillis();
                getProvider().delete(alias); 
                long deleteTime = System.currentTimeMillis() - deleteStartTime;
                logger.info(LOGTAG + "Deleted AccountAlias in " + deleteTime + " ms.");
            } 
            catch (ProviderException pe) {
                // There was an error deleting the VPC
                String errType = "application";
                String errCode = "AwsAccountService-100X";
                String errDesc = "An error occurred deleting the Stack " +
                	"The " + "exception is: " + pe.getMessage();
                logger.error(LOGTAG + errDesc);
                logger.error("Message sent in is: \n" + getMessageBody(inDoc));
                ArrayList errors = new ArrayList();
                errors.add(buildError(errType, errCode, errDesc));
                String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
                return getMessage(msg, replyContents);
            }

            // Publish a Delete-Sync Message
            try {
                MessageProducer producer = getProducerPool().getProducer();
                if (getVerbose())
                    logger.info(LOGTAG + "Publishing AccountAlias.Delete-Sync message...");
                alias.deleteSync("delete", (SyncService) producer);
                logger.info(LOGTAG + "Published AccountAlias.Delete-Sync" + " message.");
            } catch (EnterpriseObjectSyncException eose) {
                String errMsg = "An error occurred publishing the AccountAlias" 
                        + ".Delete-Sync message after deleting "
                		+ "the AccountAlias. The " + "exception is: "
                        + eose.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new CommandException(errMsg, eose);
            } catch (JMSException jmse) {
            	String errMsg = "An error occurred publishing the AccountAlias" 
                        + ".Delete-Sync message after deleting "
                		+ "the AccountAlias. The " + "exception is: "
                        + jmse.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new CommandException(errMsg, jmse);
            }

            // Remove the DataArea from the primed doc if it exists.
            if (localResponseDoc.getRootElement().getChild("DataArea") != null) {
            	localResponseDoc.getRootElement().getChild("DataArea").removeContent();
            }
            
            // Build the reply document.
            String replyContents = buildReplyDocument(eControlArea, localResponseDoc);

            // Log execution time.
            long executionTime = System.currentTimeMillis() - startTime;
            logger.info(LOGTAG + "Delete-Request command execution complete in " + executionTime + " ms.");

            // Return the response with status success.
            return getMessage(msg, replyContents);
        }

        else {
            // The messageAction is invalid; it is not a query, generate, 
        	// create. update, or delete
            String errType = "application";
            String errCode = "OpenEAI-1002";
            String errDesc = "Unsupported message action: " + msgAction + ". "
                    + "This command only supports query, generate, update, and delete.";
            logger.fatal(LOGTAG + errDesc);
            logger.fatal("Message sent in is: \n" + getMessageBody(inDoc));
            ArrayList errors = new ArrayList();
            errors.add(buildError(errType, errCode, errDesc));
            String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
            return getMessage(msg, replyContents);
        }
    }

    /**
     * @param StackProvider
     *            , the Stack provider
     *            <P>
     *            Sets the Stack provider for this command.
     */
    protected void setProvider(AccountAliasProvider provider) {
        m_provider = provider;
    }

    /**
     * @return AccountAliasProvider, the provider
     *         <P>
     *         Gets the provider for this command.
     */
    protected AccountAliasProvider getProvider() {
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
