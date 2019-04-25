/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2019 Emory University. All rights reserved. 
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


// AWS MOA objects
import com.amazon.aws.moa.objects.resources.v1_0.VirtualPrivateCloudRequisition;
import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.AccountNotification;
import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.VirtualPrivateCloudProvisioning;
import com.amazon.aws.moa.objects.resources.v1_0.AccountNotificationQuerySpecification;
import com.amazon.aws.moa.objects.resources.v1_0.VirtualPrivateCloudProvisioningQuerySpecification;

// VPC Provider Implementation
import edu.emory.awsaccount.service.provider.VirtualPrivateCloudProvisioningProvider;
import edu.emory.awsaccount.service.provider.AccountNotificationProvider;
import edu.emory.awsaccount.service.provider.ProviderException;

//Apache Commons Validators
import org.apache.commons.validator.GenericValidator;
import org.apache.commons.validator.routines.InetAddressValidator;

/**
 * This command handles requests for the AccountNotification objects.
 * Specifically, it handles query, create, update, and delete requests. 
 * For the create action it determines whether or not an AccountNotification
 * has been created for the same ARN and detector within a configurable time interval,
 * and if so it logs and does not create the AccountNotification. All other actions 
 * for the AccountNotification object are proxied to a deployment of the 
 * RDBMS connector for persistence and retrieval purposes only. 
 * <P>
 * 
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 25 April 2019
 * 
 */
public class AccountNotificationRequestCommand extends AwsAccountRequestCommand implements RequestCommand {
    private static String LOGTAG = "[AccountNotificationRequestCommand] ";
    private static Logger logger = Logger.getLogger(AccountNotificationRequestCommand.class);
    private AccountNotificationProvider m_provider = null;
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
    public AccountNotificationRequestCommand(CommandConfig cConfig) throws InstantiationException {
        super(cConfig);
        logger.info(LOGTAG + "Initializing " + ReleaseTag.getReleaseInfo());

        // Initialize a command-specific logger if it exists.
        try {
            LoggerConfig lConfig = new LoggerConfig();
            lConfig = (LoggerConfig) getAppConfig().getObjectByType(lConfig.getClass().getName());
            PropertyConfigurator.configure(lConfig.getProperties());
        } 
        catch (Exception e) {
        	String errMsg = "An error occurred configuring a command-specific " +
        		"logger. The exception is: " + e.getMessage();
        	logger.warn(LOGTAG + "No command-specific logger found.");
        }

        // Set the properties for this command.
        try {
            PropertyConfig pConfig = (PropertyConfig) getAppConfig().getObject("GeneralProperties");
            setProperties(pConfig.getProperties());
        } catch (EnterpriseConfigurationObjectException ecoe) {
            // An error occurred retrieving a property config from AppConfig.
            // Log it
            // and throw an exception.
            String errMsg = "An error occurred retrieving a property config from " 
            		+ "AppConfig. The exception is: " + ecoe.getMessage();
            logger.fatal(LOGTAG + errMsg);
            throw new InstantiationException(errMsg);
        }

        // Initialize an AccountNotificationProvider
        String className = getProperties()
        	.getProperty("accountNotificationProviderClassName");
        if (className == null || className.equals("")) {
            String errMsg = "No accountNotificationProviderClassName property "
                    + "specified. Can't continue.";
            logger.fatal(LOGTAG + errMsg);
            throw new InstantiationException(errMsg);
        }
        logger.info(LOGTAG + "accountNotificationProviderClassName" +
        	"is: " + className);
        
        AccountNotificationProvider provider = null;
        try {
            logger.info(LOGTAG + "Getting class for name: " + className);
            Class providerClass = Class.forName(className);
            if (providerClass == null)
                logger.info(LOGTAG + "providerClass is null.");
            else
                logger.info(LOGTAG + "providerClass is not null.");
            provider = (AccountNotificationProvider) Class.forName(className).newInstance();
            logger.info(LOGTAG + "Initializing AccountNotificationProvider: "
                    + provider.getClass().getName());
            provider.init(getAppConfig());
            logger.info(LOGTAG + "AccountNotificationProvider initialized.");
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
            String errMsg = "An error occurred initializing the " + "AccountNotificationProvider " + className
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
        // Get a configured AccountNotification from AppConfig.
        AccountNotification notification = new AccountNotification();
        try {
            notification = (AccountNotification) getAppConfig()
            	.getObjectByType(notification.getClass().getName());
        } 
        catch (EnterpriseConfigurationObjectException eoce) {
            String errMsg = "Error retrieving an object from AppConfig: The exception" 
            	+ "is: " + eoce.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new InstantiationException(errMsg);
        }

        // Get an AccountNotificationQuerySpecification from AppConfig.
        AccountNotificationQuerySpecification querySpec = 
        	new AccountNotificationQuerySpecification();
        try {
            querySpec = (AccountNotificationQuerySpecification) getAppConfig()
            	.getObjectByType(querySpec.getClass().getName());
        } 
        catch (EnterpriseConfigurationObjectException eoce) {
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
     *             configured VirtualPrivateCloudProvisioningProvider to perform each
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
        // VirtualPrivateCloudProvisioning object; if not, reply with an error.
        if (msgObject.equalsIgnoreCase("AccountNotification") == false) {
            String errType = "application";
            String errCode = "OpenEAI-1001";
            String errDesc = "Unsupported message object: " + msgObject
                    + ". This command expects 'AccountNotification'.";
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
        
        // Get the TestId from AppConfig
        TestId testId = new TestId();
        try {
            testId = (TestId) getAppConfig().getObjectByType(testId.getClass().getName());
        } 
        catch (EnterpriseConfigurationObjectException eoce) {
            String errMsg = "Error retrieving an object from AppConfig: " +
            	"The exception" + "is: " + eoce.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new CommandException(errMsg, eoce);
        }
        Element eTestId = inDoc.getRootElement().getChild("ControlAreaRequest")
        	.getChild("Sender").getChild("TestId");
        
        if (eTestId != null) {
        	try {
        		testId.buildObjectFromInput(eTestId);
        	}
        	catch (EnterpriseLayoutException ele) {
        		String errMsg = "Error occurred building an object from " +
                	"XML. The exception is: " + ele.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new CommandException(errMsg, ele);
        	}
        }
        
        // Handle a Query-Request.
        if (msgAction.equalsIgnoreCase("Query")) {
            logger.info(LOGTAG + "Handling an com.amazon.aws.Provisioning." +
            	"AccountNotification.Query-Request message.");
            Element eQuerySpec = inDoc.getRootElement().getChild("DataArea")
                    .getChild("AccountNotificationQuerySpecification");

            // Get a configured query object from AppConfig.            
            AccountNotificationQuerySpecification querySpec = 
            	new AccountNotificationQuerySpecification();
            try {
                querySpec = (AccountNotificationQuerySpecification) 
                	getAppConfig().getObjectByType(querySpec.getClass().getName());
            } 
            catch (EnterpriseConfigurationObjectException eoce) {
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
                    String errCode = "AwsAccontService-100X";
                    String errDesc = "An error occurred building the query " 
                    		+ "object from the DataArea element in the " 
                    		+ "Query-Request message. The exception " + "is: "
                            + ele.getMessage();
                    logger.error(LOGTAG + errDesc);
                    logger.error("Message sent in is: \n" + getMessageBody(inDoc));
                    ArrayList errors = new ArrayList();
                    errors.add(buildError(errType, errCode, errDesc));
                    String replyContents = buildReplyDocumentWithErrors(eControlArea, 
                    	localResponseDoc, errors);
                    return getMessage(msg, replyContents);
                }
            }
            else {
                // The query spec is null.
                String errType = "application";
                String errCode = "AwsAccontService-100X";
                String errDesc = "An error occurred building the query " 
                		+ "object from the DataArea element in the " 
                		+ "Query-Request message. The query spec is null.";
                logger.error(LOGTAG + errDesc);
                logger.error("Message sent in is: \n" + getMessageBody(inDoc));
                ArrayList errors = new ArrayList();
                errors.add(buildError(errType, errCode, errDesc));
                String replyContents = buildReplyDocumentWithErrors(eControlArea, 
                		localResponseDoc, errors);
                return getMessage(msg, replyContents);  
            }           
            
            // Query for the AccountNotification from the provider.
            logger.info(LOGTAG + "Querying for the AccountNotification...");

            List results = null;
            try {
            	long queryStartTime = System.currentTimeMillis();
                results = getProvider().query(querySpec);
                long queryTime = System.currentTimeMillis() - queryStartTime;
                logger.info(LOGTAG + "Queried for AccountNotification in " + queryTime + "ms.");
            } 
            catch (ProviderException pe) {
                // There was an error querying for the AccountNotification
                String errType = "application";
                String errCode = "AwsAccountService-100X";
                String errDesc = "An error occurred querying for the " 
                		+ "AccountNotification. The " + "exception is: "
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
            	logger.info(LOGTAG + "Results are null; no matching AccountNotification found.");
            }

            // Prepare the response.
            localProvideDoc.getRootElement().getChild("DataArea").removeContent();
            // If there are results, place them in the response.
            if (results != null && results.size() > 0) {
            	
                ArrayList notificationList = new ArrayList();
                for (int i = 0; i < results.size(); i++) {
                    Element eNotification = null;
                    try {
                        AccountNotification notification = 
                        	(AccountNotification) results.get(i);
                        if (eTestId != null) notification.setTestId(testId);
                        eNotification = (Element) notification.buildOutputFromObject();
                        notificationList.add(eNotification);
                    }
                    catch (EnterpriseLayoutException ele) {
                        String errMsg = "An error occurred serializing the "
                                + "AccountNotification object to an XML element."  
                        		+ " The exception is: " 
                                + ele.getMessage();
                        logger.error(LOGTAG + errMsg);
                        throw new CommandException(errMsg, ele);
                    }
                }
                localProvideDoc.getRootElement().getChild("DataArea").addContent(notificationList);
            }
            String replyContents = buildReplyDocument(eControlArea, localProvideDoc);

            // Return the response with status success.
            return getMessage(msg, replyContents);
        }     
        
        // Handle a Create-Request.
        if (msgAction.equalsIgnoreCase("Create")) {
            logger.info(LOGTAG + "Handling a com.amazon.aws.Provisioning." +
            	"AccountNotification.Create-Request message.");
            Element eVpcp = inDoc.getRootElement().getChild("DataArea")
            	.getChild("NewData").getChild("AccountNotification");

            // Verify that the AccountNotification element is not null. If it is
            // null, reply with an error.
            if (eVpcp == null) {
                String errType = "application";
                String errCode = "OpenEAI-1015";
                String errDesc = "Invalid element found in the Create-Request "
                        + "message. This command expects an AccountNotification";
                logger.error(LOGTAG + errDesc);
                logger.error("Message sent in is: \n" + getMessageBody(inDoc));
                ArrayList errors = new ArrayList();
                errors.add(buildError(errType, errCode, errDesc));
                String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
                return getMessage(msg, replyContents);
            }
            
            // Get a configured AccountNotification from AppConfig.
            AccountNotification notification = new AccountNotification();
            try {
                notification = (AccountNotification) getAppConfig()
                	.getObjectByType(notification.getClass().getName());
            } catch (EnterpriseConfigurationObjectException eoce) {
                String errMsg = "Error retrieving an object from AppConfig: " +
                	"The exception is: " + eoce.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new CommandException(errMsg);
            }

            // Now build a virtual private cloud provisioning object from the element in the
            // message.
            try {
                notification.buildObjectFromInput(eVpcp);
                if (eTestId != null) { 
                	testId.buildObjectFromInput(eTestId);
                	notification.setTestId(testId);
                }
                logger.info(LOGTAG + "TestId is: " + notification.getTestId().toString());
            } 
            catch (EnterpriseLayoutException ele) {
                // There was an error building the delete object from the
                // delete element.
                String errType = "application";
                String errCode = "AwsAccountService-100X";
                String errDesc = "An error occurred building the create object " +
                		"from the DataArea element in the Create-Request " +
                		"message. The exception " + "is: " + ele.getMessage();
                logger.error(LOGTAG + errDesc);
                logger.error("Message sent in is: \n" + getMessageBody(inDoc));
                ArrayList errors = new ArrayList();
                errors.add(buildError(errType, errCode, errDesc));
                String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
                return getMessage(msg, replyContents);
            }

            // Create the AccountNotification object using the provider.
            logger.info(LOGTAG + "Creating an AccountNotification...");

            try {
            	long createStartTime = System.currentTimeMillis();
                getProvider().create(notification); 
                long createTime = System.currentTimeMillis() - createStartTime;
                logger.info(LOGTAG + "Created the AccountNotification in " + createTime + " ms.");
            } 
            catch (ProviderException pe) {
                // There was an error creating the VPC
                String errType = "application";
                String errCode = "AwsAccountService-100X";
                String errDesc = "An error occurred creating the AccountNotification" +
                	" The " + "exception is: " + pe.getMessage();
                logger.error(LOGTAG + errDesc);
                logger.error("Message sent in is: \n" + getMessageBody(inDoc));
                ArrayList errors = new ArrayList();
                errors.add(buildError(errType, errCode, errDesc));
                String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
                return getMessage(msg, replyContents);
            }
            
            // No need to public a Create-Sync, because it will be done by the
            // underlying RDBMS connector infrastructure

            // Remove the DataArea from the primed doc if it exists.
            if (localResponseDoc.getRootElement().getChild("DataArea") != null) {
            	localResponseDoc.getRootElement().getChild("DataArea").removeContent();
            }
            
            // Build the reply contents
            String replyContents = buildReplyDocument(eControlArea, localResponseDoc);

            // Log execution time.
            long executionTime = System.currentTimeMillis() - startTime;
            logger.info(LOGTAG + "Create-Request command execution complete in " + executionTime + " ms.");

            // Return the response with status success.
            return getMessage(msg, replyContents);
        }
        
        // Handle an Update-Request.
        if (msgAction.equalsIgnoreCase("Update")) {
            logger.info(LOGTAG + "Handling a com.amazon.aws.Provisioning." +
            		"AccountNotification.Update-Request message.");

            // Verify that the baseline is not null.
            Element eBaselineData = inDoc.getRootElement().getChild("DataArea").getChild("BaselineData")
                    .getChild("AccountNotification");
            Element eNewData = inDoc.getRootElement().getChild("DataArea").getChild("NewData")
                    .getChild("AccountNotification");
            if (eNewData == null || eBaselineData == null) {
                String errMsg = "Either the baseline or new data state of the "
                        + "AccountNotification is null. Can't continue.";
                throw new CommandException(errMsg);
            }

            // Get configured objects from AppConfig.
            AccountNotification baselineNotification = new AccountNotification();
            AccountNotification newNotification = new AccountNotification();
            try {
                baselineNotification = (AccountNotification) getAppConfig()
                	.getObjectByType(baselineNotification.getClass().getName());
                newNotification = (AccountNotification) getAppConfig()
                	.getObjectByType(newNotification.getClass().getName());
            } 
            catch (EnterpriseConfigurationObjectException ecoe) {
                String errMsg = "An error occurred retrieving an object from " 
                	+ "AppConfig. The exception is: " + ecoe.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new CommandException(errMsg, ecoe);
            }
            if (eTestId != null)
                newNotification.setTestId(testId);

            // Build the baseline and newdata states of the VPCP.
            try {
                baselineNotification.buildObjectFromInput(eBaselineData);
                newNotification.buildObjectFromInput(eNewData);
                if (eTestId != null) { 
                	testId.buildObjectFromInput(eTestId);
                	newNotification.setTestId(testId);
                }
                logger.info(LOGTAG + "TestId is: " + newNotification.getTestId().toString());
            } catch (EnterpriseLayoutException ele) {
                String errMsg = "An error occurred building the baseline and newdata"
                        + " states of the AccountNotification object passed in. " 
                		+ "The exception is: " + ele.getMessage();
                throw new CommandException(errMsg, ele);
            }

            // Perform the baseline check.
            
            // Get a configured AccountNotificationQuerySpecofication from
            // AppConfig.
            AccountNotificationQuerySpecification querySpec = 
            	new AccountNotificationQuerySpecification();
            try {
                querySpec = (AccountNotificationQuerySpecification) getAppConfig()
                	.getObjectByType(querySpec.getClass().getName());
            } catch (EnterpriseConfigurationObjectException eoce) {
                String errMsg = "Error retrieving an object from AppConfig: "
                	+ "The exception" + "is: " + eoce.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new CommandException(errMsg);
            }
            
            // Set the value of the VpcId.
            try {
            	querySpec.setAccountNotificationId(baselineNotification
            		.getAccountNotificationId());
            }
            catch (EnterpriseFieldException efe) {
            	String errMsg = "An error occurred setting the field values " +
            		"of the query specification. The exception is: " +
            		efe.getMessage();
            	logger.error(LOGTAG + errMsg);
            	throw new CommandException(errMsg, efe);
            }
          
            // Query for the AccountNotification.
            AccountNotification notification = null;
            try {
            	logger.info(LOGTAG + "Querying for baseline AccountNotification...");
                List notificationList = getProvider().query(querySpec);
                logger.info(LOGTAG + "Found " + notificationList.size() + " result(s).");
                if (notificationList.size() > 0) notification = (AccountNotification)notificationList.get(0);
            } catch (ProviderException pe) {
                // There was an error querying the VPCP service
                String errType = "application";
                String errCode = "AmazonAccountService-100X";
                String errDesc = "An error occurred querying the AccountNotificationProvider " 
                		+ "to verify the baseline state of the AccountNotification. " 
                		+ "The exception is: " + pe.getMessage();
                logger.error(LOGTAG + errDesc);
                logger.error("Message sent in is: \n" + getMessageBody(inDoc));
                ArrayList errors = new ArrayList();
                errors.add(buildError(errType, errCode, errDesc));
                String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
                return getMessage(msg, replyContents);
            }
           
            if (notification != null) {
                // Compare the retrieved baseline with the baseline in the
                // update request message.
                try {
                    if (baselineNotification.equals(notification)) {
                        logger.info(LOGTAG + "Baseline matches the current " +
                        	"state of the AccountNotification in the AWS Account Service.");
                    } else {
                        logger.info(LOGTAG + "Baseline does not match the " +
                        	"current state of the AccountNotification in the AWS Account " +
                        	"Serivce.");
                        String errType = "application";
                        String errCode = "OpenEAI-1014";
                        String errDesc = "Baseline is stale.";
                        logger.error(LOGTAG + errDesc);
                        logger.error("Message sent in is: \n" + getMessageBody(inDoc));
                        ArrayList errors = new ArrayList();
                        errors.add(buildError(errType, errCode, errDesc));
                        String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
                        return getMessage(msg, replyContents);
                    }
                } catch (XmlEnterpriseObjectException xeoe) {
                    // TODO: error handling
                    logger.error(LOGTAG + xeoe.getMessage());
                }

            } else {
                // Respond with an error, because no AccountNotification matching the
            	// baseline could be found.
                String errType = "application";
                String errCode = "OpenEAI-1014";
                String errDesc = "Baseline is stale. No baseline found.";
                logger.error(LOGTAG + errDesc);
                logger.error("Message sent in is: \n" + getMessageBody(inDoc));
                ArrayList errors = new ArrayList();
                errors.add(buildError(errType, errCode, errDesc));
                String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
                return getMessage(msg, replyContents);
            }

            // Verify that the baseline and the new state are not equal.
            try {
                if (baselineNotification.equals(newNotification)) {
                    String errType = "application";
                    String errCode = "AwsAccountService-100X";
                    String errDesc = "Baseline state and new state of the "
                            + "AccountNotification are equal. No update operation " 
                    		+ "may be performed.";
                    logger.error(LOGTAG + errDesc);
                    logger.error("Message sent in is: \n" + getMessageBody(inDoc));
                    ArrayList errors = new ArrayList();
                    errors.add(buildError(errType, errCode, errDesc));
                    String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
                    return getMessage(msg, replyContents);
                }
            } catch (XmlEnterpriseObjectException xeoe) {
                String errMsg = "An error occurred comparing the baseline and " + "new data. The exception is: "
                        + xeoe.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new CommandException(errMsg, xeoe);
            }

            // Update the AccountNotification object using the provider.
            try {
                long updateStartTime = System.currentTimeMillis();
                logger.info(LOGTAG + "Updating the AccountNotification " +
                	"in the AccountNotificationProvider...");
                getProvider().update(newNotification);
                long updateTime = System.currentTimeMillis() - updateStartTime;
                logger.info(LOGTAG + "AccountNotification update processed by " 
                	+ "AccountNotificationProvider in " + updateTime + " ms.");
            } catch (ProviderException pe) {
                // There was an error updating the VPC
                String errType = "application";
                String errCode = "AwsAccountService-1002";
                String errDesc = "An error occurred updating the object. The " + "exception is: " + pe.getMessage();
                logger.error(LOGTAG + errDesc);
                logger.error("Message sent in is: \n" + getMessageBody(inDoc));
                ArrayList errors = new ArrayList();
                errors.add(buildError(errType, errCode, errDesc));
                String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
                return getMessage(msg, replyContents);
            }
            logger.info(LOGTAG + "Updated AccountNotification: " + newNotification.toString());

            // Set the baseline on the new state of the AccountNotification.
            newNotification.setBaseline(baselineNotification);          
            
            // No need to public an Update-Sync, because it will be done by the
            // underlying RDBMS connector infrastructure

            // Remove the DataArea from the primed doc if it exists.
            if (localResponseDoc.getRootElement().getChild("DataArea") != null) {
            	localResponseDoc.getRootElement().getChild("DataArea").removeContent();
            }
            
            // Build the reply document.
            String replyContents = buildReplyDocument(eControlArea, localResponseDoc);

            // Log execution time.
            long executionTime = System.currentTimeMillis() - startTime;
            logger.info(LOGTAG + "Update-Request command execution complete in " + executionTime + " ms.");

            // Return the response with status success.
            return getMessage(msg, replyContents);
        }         
        
        // Handle a Delete-Request.
        if (msgAction.equalsIgnoreCase("Delete")) {
            logger.info(LOGTAG + "Handling a com.amazon.aws.Provisioning." +
            	"AccountNotification.Delete-Request message.");
            Element eVpc = inDoc.getRootElement().getChild("DataArea")
            	.getChild("DeleteData").getChild("AccountNotification");

            // Verify that the AccountNotification element is not null. If it is
            // null, reply with an error.
            if (eVpc == null) {
                String errType = "application";
                String errCode = "OpenEAI-1015";
                String errDesc = "Invalid element found in the Delete-Request "
                        + "message. This command expects an AccountNotification";
                logger.error(LOGTAG + errDesc);
                logger.error("Message sent in is: \n" + getMessageBody(inDoc));
                ArrayList errors = new ArrayList();
                errors.add(buildError(errType, errCode, errDesc));
                String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
                return getMessage(msg, replyContents);
            }
            
            // Get a configured AccountNotification from AppConfig.
            AccountNotification notification = new AccountNotification();
            try {
                notification = (AccountNotification) getAppConfig()
                	.getObjectByType(notification.getClass().getName());
            } catch (EnterpriseConfigurationObjectException eoce) {
                String errMsg = "Error retrieving an object from AppConfig: " +
                	"The exception" + "is: " + eoce.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new CommandException(errMsg);
            }           
            
            // Now build an AccountNotification object from the element in the
            // message.
            try {
                notification.buildObjectFromInput(eVpc);
                if (eTestId != null) testId.buildObjectFromInput(eTestId);
                notification.setTestId(testId);
            } 
            catch (EnterpriseLayoutException ele) {
                // There was an error building the delete object from the
                // delete element.
                String errType = "application";
                String errCode = "AwsAccountService-100X";
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

            // Delete the AccountNotification object using the provider.
            logger.info(LOGTAG + "Deleting the AccountNotification...");

            try {
            	long deleteStartTime = System.currentTimeMillis();
                getProvider().delete(notification); 
                long deleteTime = System.currentTimeMillis() - deleteStartTime;
                logger.info(LOGTAG + "Deleted AccountNotification in " + deleteTime + " ms.");
            } 
            catch (ProviderException pe) {
                // There was an error deleting the VPC
                String errType = "application";
                String errCode = "AwsAccountService-100X";
                String errDesc = "An error occurred deleting the AccountNotification" +
                	" The " + "exception is: " + pe.getMessage();
                logger.error(LOGTAG + errDesc);
                logger.error("Message sent in is: \n" + getMessageBody(inDoc));
                ArrayList errors = new ArrayList();
                errors.add(buildError(errType, errCode, errDesc));
                String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
                return getMessage(msg, replyContents);
            }

            // No need to public a Delete-Sync, because it will be done by the
            // underlying RDBMS connector infrastructure

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
                    + "This command only supports query, create, update, and delete.";
            logger.fatal(LOGTAG + errDesc);
            logger.fatal("Message sent in is: \n" + getMessageBody(inDoc));
            ArrayList errors = new ArrayList();
            errors.add(buildError(errType, errCode, errDesc));
            String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
            return getMessage(msg, replyContents);
        }
    }

    /**
     * @param AccountNotificationProvisioningProvider
     *            , the notificationprovider
     *            <P>
     *            Sets the AccountNotificationProvider for this command.
     */
    protected void setProvider(AccountNotificationProvider provider) {
        m_provider = provider;
    }

    /**
     * @return AccountNotificationProvider, the notification provider provider
     *         <P>
     *         Gets the AccountNotification provider for this command.
     */
    protected AccountNotificationProvider getProvider() {
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
