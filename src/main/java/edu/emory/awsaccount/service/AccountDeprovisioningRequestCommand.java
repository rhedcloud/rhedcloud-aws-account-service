/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2016 Emory University. All rights reserved.
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
import com.amazon.aws.moa.objects.resources.v1_0.AccountDeprovisioningRequisition;
import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.AccountDeprovisioning;
import com.amazon.aws.moa.objects.resources.v1_0.AccountDeprovisioningQuerySpecification;

// AccountDeprovisioningProvider Implementation
import edu.emory.awsaccount.service.provider.AccountDeprovisioningProvider;
import edu.emory.awsaccount.service.provider.ProviderException;

//Apache Commons Validators
import org.apache.commons.validator.GenericValidator;
import org.apache.commons.validator.routines.InetAddressValidator;

/**
 * This command handles requests for AccountDeprovisioning objects.
 * Specifically, it handles a Generate-Request. All other actions for the
 * AccountDeprovisioning object are handled by a deployment of the
 * RDBMS connector for persistence and retrieval purposes only. This command
 * also proxies query requests to the RDBMS connector implementation, so
 * one command and one endpoint can cleanly implement the entire public
 * interface for account deprovisioning.
 * <P>
 * The generate and query actions for AccountDeprovisioning are
 * invoked by clients wanting to deprovision an existing AWS account.
 * The generate operation passes in an AccountDeprovisioningRequisition object
 * with the account number to deprovision. The generate action immediately returns
 * a AccountDeprovisioning object with detailed status of the
 * deprovisioning process and a DeprovisioningId that can be used for subsequent
 * queries for updates on the progress of provisioning. Additionally, like
 * all similar services the AwsAccountService also publishes create, update, and
 * delete sync messages, so as a new instance of the deprovisioning process
 * is created and updated, create and update sync messages are published.
 * Applications interested in the status of provisioning may also consume
 * these messages to take action on provisioning operations.
 * <OL>
 * <LI>com.amazon.aws.Provisioning.AccountDeprovisioning.Query-Request (<A HREF=
 * "https://svn.service.emory.edu:8443/cgi-bin/viewvc.cgi/emoryoit/message/releases/com/amazon/aws/Provisioning/AccountDeprovisioning/1.0/dtd/Query-Request.dtd?view=markup"
 * >Definition</A> | <A HREF=
 * "https://svn.service.emory.edu:8443/cgi-bin/viewvc.cgi/emoryoit/message/releases/com/amazon/aws/Provisioning/AccountDeprovisioning/1.0/xml/Query-Request.xml?view=markup"
 * >Sample Message</A>)
 * <UL>
 * <LI>Convert the JMS message to and XML document</LI>
 * <LI>Build a message object from the XML document for the
 * AccountDeprovisioningQuerySpecification</LI>
 * <LI>Get a configured AccountDeprovisioning object from
 * AppConfig</LI>
 * <LI>Get the P2P producer pool configured to send messages to the
 * AwsAccountService RDBMS command implementation</LI>
 * <LI>Call the query method on the VirtualPrivateCloudProvisioiningObject</LI>
 * <LI>Proxy the result to the requestor by building the message response from
 * the results of the query operation. This should contain a list of
 * zero or more AccountDeprovisioning objects.</LI>
 * <LI>Return the response.</LI>
 * </UL>
 * </LI>
 * <LI>com.amazon.aws.Provisioning.AccountDeprovisioning.Generate-Request (<A
 * HREF=
 * "https://svn.service.emory.edu:8443/cgi-bin/viewvc.cgi/emoryoit/message/releases/com/amazon/aws/Provisioning/AccountDeprovisioning/1.0/dtd/Generate-Request.dtd?view=markup"
 * >Definition</A> | <A HREF=
 * "https://svn.service.emory.edu:8443/cgi-bin/viewvc.cgi/emoryoit/message/releases/com/amazon/aws/Provisioning/AccountDeprovisioning/1.0/xml/Generate-Request.xml?view=markup"
 * >Sample Message</A>)
 * <UL>
 * <LI>Convert the JMS message to and XML document</LI>
 * <LI>Build a message object from the XML document for the
 * VirtualPrivateCloudRequisition object</LI>
 * <LI>Invoke the generate method of the
 * configured account deprovisioning provider. </LI>
 * <LI>Build the response to the request message</LI>
 * <LI>Return the response to the request message</LI>
 * </UL>
 * </LI>
 *
 * </OL>
 *
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 2 May 2016
 *
 */
public class AccountDeprovisioningRequestCommand extends AwsAccountRequestCommand implements RequestCommand {
    private static String LOGTAG = "[AccountDeprovisioningRequestCommand] ";
    private static Logger logger = Logger.getLogger(AccountDeprovisioningRequestCommand.class);
    private AccountDeprovisioningProvider m_provider = null;
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
    public AccountDeprovisioningRequestCommand(CommandConfig cConfig) throws InstantiationException {
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
            logger.info(LOGTAG + "Properties are: " + getProperties().toString());
        }
        catch (EnterpriseConfigurationObjectException ecoe) {
            // An error occurred retrieving a property config from AppConfig.
            // Log it
            // and throw an exception.
            String errMsg = "An error occurred retrieving a property config from "
            		+ "AppConfig. The exception is: " + ecoe.getMessage();
            logger.fatal(LOGTAG + errMsg);
            throw new InstantiationException(errMsg);
        }

        // Initialize an AccountDeprovisioningProvider
        String className = getProperties()
        	.getProperty("accountDeprovisioningProviderClassName");
        if (className == null || className.equals("")) {
            String errMsg = "No accountDeprovisioningProviderClassName property "
                    + "specified. Can't continue.";
            logger.fatal(LOGTAG + errMsg);
            throw new InstantiationException(errMsg);
        }
        logger.info(LOGTAG + "accountDeprovisioningProviderClassName" +
        	"is: " + className);

        AccountDeprovisioningProvider provider = null;
        try {
            logger.info(LOGTAG + "Getting class for name: " + className);
            Class providerClass = Class.forName(className);
            if (providerClass == null)
                logger.info(LOGTAG + "providerClass is null.");
            else
                logger.info(LOGTAG + "providerClass is not null.");
            provider = (AccountDeprovisioningProvider) Class.forName(className).newInstance();
            logger.info(LOGTAG + "Initializing AccountDeprovisioningProvider: "
                    + provider.getClass().getName());
            provider.init(getAppConfig());
            logger.info(LOGTAG + "AccountDeprovisioningProvider initialized.");
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
            String errMsg = "An error occurred initializing the " + "AccountDeprovisioningProvider " + className
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
        // Get a configured AccountDeprovisioning from AppConfig.
        AccountDeprovisioning ad = new AccountDeprovisioning();
        try {
            ad = (AccountDeprovisioning) getAppConfig()
            	.getObjectByType(ad.getClass().getName());
        } catch (EnterpriseConfigurationObjectException eoce) {
            String errMsg = "Error retrieving an object from AppConfig: The exception" + "is: "
            	+ eoce.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new InstantiationException(errMsg);
        }

        // Get a AccountDeprovisiningRequisition from AppConfig.
        AccountDeprovisioningRequisition req = new AccountDeprovisioningRequisition();
        try {
            req = (AccountDeprovisioningRequisition) getAppConfig()
            	.getObjectByType(req.getClass().getName());
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
     *             an AccountDeprovisioning object and the action is a query,
     *             generate, update, or delete. Then this method uses the
     *             configured AccountDeprovisioningProvider to perform each
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
        // AccountDeprovisioning object; if not, reply with an error.
        if (msgObject.equalsIgnoreCase("AccountDeprovisioning") == false) {
            String errType = "application";
            String errCode = "OpenEAI-1001";
            String errDesc = "Unsupported message object: " + msgObject
                    + ". This command expects 'AccountDeprovisioning'.";
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

        // Get a configured AccountDeprovisioningRequisition from AppConfig.
        AccountDeprovisioningRequisition req = new AccountDeprovisioningRequisition();
        TestId testId = new TestId();
        try {
            req = (AccountDeprovisioningRequisition) getAppConfig().getObjectByType(req.getClass().getName());
            testId = (TestId) getAppConfig().getObjectByType(testId.getClass().getName());
        } catch (EnterpriseConfigurationObjectException eoce) {
            String errMsg = "Error retrieving an object from AppConfig: The exception" + "is: " + eoce.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new CommandException(errMsg, eoce);
        }
        Element eTestId = inDoc.getRootElement().getChild("ControlAreaRequest").getChild("Sender").getChild("TestId");

        // Handle a Generate-Request.
        if (msgAction.equalsIgnoreCase("Generate")) {
            logger.info(LOGTAG + "Handling a com.amazon.aws.Provisioning.AccountDeprovisioning.Generate-Request"
                    + " message.");
            Element eGenerateObject = inDoc.getRootElement().getChild("DataArea").getChild("AccountDeprovisioningRequisition");

            // Verify that the generate object element is not null. If it is
            // null, reply
            // with an error.
            if (eGenerateObject == null) {
                String errType = "application";
                String errCode = "OpenEAI-1015";
                String errDesc = "Invalid element found in the Generate-Request "
                        + "message. This command expects an AccountDeprovisioningRequisition";
                logger.error(LOGTAG + errDesc);
                logger.error("Message sent in is: \n" + getMessageBody(inDoc));
                ArrayList errors = new ArrayList();
                errors.add(buildError(errType, errCode, errDesc));
                String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
                return getMessage(msg, replyContents);
            }

            // Now build a AccountDeprovisioningRequisition object from the element in the message.
            try {
                req.buildObjectFromInput(eGenerateObject);
                if (eTestId != null)
                    testId.buildObjectFromInput(eTestId);
            } catch (EnterpriseLayoutException ele) {
                // There was an error building the query object from a query
                // element.
                String errType = "application";
                String errCode = "MppiService-1001";
                String errDesc = "An error occurred building the generate object from "
                        + "the DataArea element in the Generate-Request message. The exception " + "is: "
                        + ele.getMessage();
                logger.error(LOGTAG + errDesc);
                logger.error("Message sent in is: \n" + getMessageBody(inDoc));
                ArrayList errors = new ArrayList();
                errors.add(buildError(errType, errCode, errDesc));
                String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
                return getMessage(msg, replyContents);
            }

            // Generate the AccountDeprovisioning object using the provider implementation.
            logger.info(LOGTAG + "Generating an AccountDeprovisioning object...");

            AccountDeprovisioning ad = null;
            try {
            	long generateStartTime = System.currentTimeMillis();
                ad = getProvider().generate(req);
                long generateTime = System.currentTimeMillis() - generateStartTime;
                if (getVerbose()) logger.info(LOGTAG + "Generated AccountDeprovisioning in " + generateTime + " ms." );
                if (eTestId != null)
                    ad.setTestId(testId);
            } catch (Throwable pe) {
                logger.error(LOGTAG, pe);
                // There was an error generating the identity
                String errType = "application";
                String errCode = "AwsAccountService-1003";
                String errDesc = "An error occurred generating the AccountDeprovisioning object. The " + "exception is: " + pe.getMessage();
                logger.error(LOGTAG + errDesc);
                logger.error("Message sent in is: \n" + getMessageBody(inDoc));
                ArrayList errors = new ArrayList();
                errors.add(buildError(errType, errCode, errDesc));
                String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
                return getMessage(msg, replyContents);
            }

            logger.info(LOGTAG + "Publishing sync... " );
            // Publish a Create-Sync Message
            try {
                MessageProducer producer = getProducerPool().getProducer();
                if (getVerbose())
                    logger.info(LOGTAG + "Publishing an AccountDeprovisioining.Create-Sync message...");
                Authentication auth = new Authentication();
                auth.setAuthUserId(authUserId);
                auth.setAuthUserSignature("none");
                ad.setAuthentication(auth);
                ad.createSync((SyncService) producer);
                logger.info(LOGTAG + "Published AccountDeprovisioning.Create-Sync" + " message.");
            } catch (EnterpriseObjectSyncException eose) {
                String errMsg = "An error occurred publishing the AccountDeprovisioning"
                        + ".Create-Sync message. The " + "exception is: "
                        + eose.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new CommandException(errMsg, eose);
            } catch (JMSException jmse) {
                String errMsg = "An error occurred publishing the AccountDeprovisioning"
                        + ".Create-Sync message. The " + "exception is: "
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
            Element eVpcp = null;
            try {
                eVpcp = (Element) ad.buildOutputFromObject();
            } catch (EnterpriseLayoutException ele) {
                String errMsg = "An error occurred serializing a AccountDeprovisioning " +
                	"object to an XML element. The exception is: " + ele.getMessage();
                logger.error(LOGTAG + errMsg, ele);
                throw new CommandException(errMsg, ele);
            }
            localResponseDoc.getRootElement().getChild("DataArea").addContent(eVpcp);
            String replyContents = buildReplyDocument(eControlArea, localResponseDoc);

            // Log execution time.
            long executionTime = System.currentTimeMillis() - startTime;
            logger.info(LOGTAG + "Generate-Request command execution complete in " + executionTime + " ms.");

            // Return the response with status success.
            return getMessage(msg, replyContents);
        }

        // Handle a Query-Request.
        if (msgAction.equalsIgnoreCase("Query")) {
            logger.info(LOGTAG + "Handling an com.amazon.aws.Provisioning.AccountDeprovisioning."
                    + "Query-Request message.");
            Element eQuerySpec = inDoc.getRootElement().getChild("DataArea")
                    .getChild("AccountDeprovisioningQuerySpecification");

            // Get a configured query object from AppConfig.
            AccountDeprovisioningQuerySpecification querySpec = new AccountDeprovisioningQuerySpecification();
            try {
                querySpec = (AccountDeprovisioningQuerySpecification) getAppConfig().getObjectByType(
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
                    String errCode = "AwsAccontService-100X";
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
                String errCode = "AwsAccontService-100X";
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

            // Query for the AccountDeprovisioning from the provider.
            logger.info(LOGTAG + "Querying for the AccountDeprovisioning...");

            List results = null;
            try {
            	long queryStartTime = System.currentTimeMillis();
                results = getProvider().query(querySpec);
                long queryTime = System.currentTimeMillis() - queryStartTime;
                logger.info(LOGTAG + "Queried for AccountDeprovisioning in " + queryTime + "ms.");
            } catch (ProviderException pe) {
                // There was an error generating the identity
                String errType = "application";
                String errCode = "AwsAccountService-100X";
                String errDesc = "An error occurred querying for the Virtual"
                		+ "PrivateCloud. The " + "exception is: "
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
            	logger.info(LOGTAG + "Results are null; no matching AccountDeprovisioning found.");
            }

            // Prepare the response.
            localProvideDoc.getRootElement().getChild("DataArea").removeContent();
            // If there are results, place them in the response.
            if (results != null && results.size() > 0) {

                ArrayList adList = new ArrayList();
                for (int i = 0; i < results.size(); i++) {
                    Element eAccountDeprovisioning = null;
                    try {
                        AccountDeprovisioning ad = (AccountDeprovisioning) results.get(i);
                        eAccountDeprovisioning = (Element) ad.buildOutputFromObject();
                        adList.add(eAccountDeprovisioning);
                        if (eTestId != null)
                            ad.setTestId(testId);
                    } catch (EnterpriseLayoutException ele) {
                        String errMsg = "An error occurred serializing "
                                + "AccountDeprovisioning object "
                        		+ "to an XML element. The exception is: "
                                + ele.getMessage();
                        logger.error(LOGTAG + errMsg);
                        throw new CommandException(errMsg, ele);
                    }
                }
                localProvideDoc.getRootElement().getChild("DataArea").addContent(adList);
            }
            String replyContents = buildReplyDocument(eControlArea, localProvideDoc);

            // Return the response with status success.
            return getMessage(msg, replyContents);
        }

        // Handle a Create-Request.
        if (msgAction.equalsIgnoreCase("Create")) {
            logger.info(LOGTAG + "Handling a com.amazon.aws.Provisioning." +
            	"AccountDeprovisioning.Create-Request message.");
            Element eVpcp = inDoc.getRootElement().getChild("DataArea")
            	.getChild("NewData").getChild("AccountDeprovisioning");

            // Verify that the AccountDeprovisioning element is not null. If it is
            // null, reply with an error.
            if (eVpcp == null) {
                String errType = "application";
                String errCode = "OpenEAI-1015";
                String errDesc = "Invalid element found in the Create-Request "
                        + "message. This command expects an AccountDeprovisioning";
                logger.error(LOGTAG + errDesc);
                logger.error("Message sent in is: \n" + getMessageBody(inDoc));
                ArrayList errors = new ArrayList();
                errors.add(buildError(errType, errCode, errDesc));
                String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
                return getMessage(msg, replyContents);
            }

            // Get a configured AccountDeprovisioning from AppConfig.
            AccountDeprovisioning ad = new AccountDeprovisioning();
            try {
                ad = (AccountDeprovisioning) getAppConfig()
                	.getObjectByType(ad.getClass().getName());
            } catch (EnterpriseConfigurationObjectException eoce) {
                String errMsg = "Error retrieving an object from AppConfig: The exception" + "is: " + eoce.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new CommandException(errMsg);
            }

            // Now build an AccountDeprovisioning object from the element in the
            // message.
            try {
                ad.buildObjectFromInput(eVpcp);
                if (eTestId != null) {
                	testId.buildObjectFromInput(eTestId);
                	ad.setTestId(testId);
                }
                logger.info(LOGTAG + "TestId is: " + ad.getTestId().toString());
            }
            catch (EnterpriseLayoutException ele) {
                // There was an error building the delete object from the
                // delete element.
                String errType = "application";
                String errCode = "AwsAccountService-100X";
                String errDesc = "An error occurred building the delete object " +
                		"from the DataArea element in the Create-Request " +
                		"message. The exception " + "is: " + ele.getMessage();
                logger.error(LOGTAG + errDesc);
                logger.error("Message sent in is: \n" + getMessageBody(inDoc));
                ArrayList errors = new ArrayList();
                errors.add(buildError(errType, errCode, errDesc));
                String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
                return getMessage(msg, replyContents);
            }

            // Create the AccountDeprovisioning object using the provider.
            logger.info(LOGTAG + "Creating an AccountDeprovisioning...");

            try {
            	long createStartTime = System.currentTimeMillis();
                getProvider().create(ad);
                long createTime = System.currentTimeMillis() - createStartTime;
                logger.info(LOGTAG + "Created AccountDeprovisioning in " + createTime + " ms.");
            }
            catch (ProviderException pe) {
                // There was an error creating the AccountDeprovisioning
                String errType = "application";
                String errCode = "AwsAccountService-100X";
                String errDesc = "An error occurred deleting the Account" +
                	"Deprovisioning. The " + "exception is: " + pe.getMessage();
                logger.error(LOGTAG + errDesc);
                logger.error("Message sent in is: \n" + getMessageBody(inDoc));
                ArrayList errors = new ArrayList();
                errors.add(buildError(errType, errCode, errDesc));
                String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
                return getMessage(msg, replyContents);
            }

            // Publish a Create-Sync Message
            try {
                MessageProducer producer = getProducerPool().getProducer();
                if (getVerbose())
                    logger.info(LOGTAG + "Publishing AccountDeprovisioning.Create-Sync message...");
                ad.createSync((SyncService) producer);
                logger.info(LOGTAG + "Published AccountDeprovisioning.Create-Sync" + " message.");
            } catch (EnterpriseObjectSyncException eose) {
                String errMsg = "An error occurred publishing the Account"
                        + "Deprovisioning.Create-Sync message after creating "
                		+ "the AccountDeprovisioning object. The " + "exception is: "
                        + eose.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new CommandException(errMsg, eose);
            } catch (JMSException jmse) {
            	String errMsg = "An error occurred publishing the Account"
                        + "Deprovisioning.Create-Sync message after creating "
                		+ "the AccountDeprovisioning object. The " + "exception is: "
                        + jmse.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new CommandException(errMsg, jmse);
            }

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
            		"AccountDeprovisioning.Update-Request message.");

            // Verify that the baseline is not null.
            Element eBaselineData = inDoc.getRootElement().getChild("DataArea").getChild("BaselineData")
                    .getChild("AccountDeprovisioning");
            Element eNewData = inDoc.getRootElement().getChild("DataArea").getChild("NewData")
                    .getChild("AccountDeprovisioning");
            if (eNewData == null || eBaselineData == null) {
                String errMsg = "Either the baseline or new data state of the "
                        + "AccountDeprovisioning is null. Can't continue.";
                throw new CommandException(errMsg);
            }

            // Get configured objects from AppConfig.
            AccountDeprovisioning baselineAd = new AccountDeprovisioning();
            AccountDeprovisioning newAd = new AccountDeprovisioning();
            try {
                baselineAd = (AccountDeprovisioning) getAppConfig()
                	.getObjectByType(baselineAd.getClass().getName());
                newAd = (AccountDeprovisioning) getAppConfig()
                	.getObjectByType(newAd.getClass().getName());
            }
            catch (EnterpriseConfigurationObjectException ecoe) {
                String errMsg = "An error occurred retrieving an object from "
                	+ "AppConfig. The exception is: " + ecoe.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new CommandException(errMsg, ecoe);
            }
            if (eTestId != null)
                newAd.setTestId(testId);

            // Build the baseline and newdata states of the AccountDeprovisioning.
            try {
                baselineAd.buildObjectFromInput(eBaselineData);
                newAd.buildObjectFromInput(eNewData);
                if (eTestId != null) {
                	testId.buildObjectFromInput(eTestId);
                	newAd.setTestId(testId);
                }
                logger.info(LOGTAG + "TestId is: " + newAd.getTestId().toString());
            } catch (EnterpriseLayoutException ele) {
                String errMsg = "An error occurred building the baseline and newdata"
                        + " states of the AccountDeprovisioning object passed in. "
                		+ "The exception is: " + ele.getMessage();
                throw new CommandException(errMsg, ele);
            }

            // Perform the baseline check.

            // Get a configured AccountDeprovisioningQuerySpecification from
            // AppConfig.
            AccountDeprovisioningQuerySpecification querySpec =
            	new AccountDeprovisioningQuerySpecification();
            try {
                querySpec = (AccountDeprovisioningQuerySpecification) getAppConfig()
                	.getObjectByType(querySpec.getClass().getName());
            } catch (EnterpriseConfigurationObjectException eoce) {
                String errMsg = "Error retrieving an object from AppConfig: "
                	+ "The exception" + "is: " + eoce.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new CommandException(errMsg);
            }

            // Set the value of the DeprovisioningId.
            try {
            	querySpec.setDeprovisioningId(baselineAd.getDeprovisioningId());
            }
            catch (EnterpriseFieldException efe) {
            	String errMsg = "An error occurred setting the field values " +
            		"of the VPCP query specification. The exception is: " +
            		efe.getMessage();
            	logger.error(LOGTAG + errMsg);
            	throw new CommandException(errMsg, efe);
            }

            // Query for the AccountDeprovisioning.
            AccountDeprovisioning ad = null;
            try {
            	logger.info(LOGTAG + "Querying for baseline AccountDeprovisioning...");
                List adList = getProvider().query(querySpec);
                logger.info(LOGTAG + "Found " + adList.size() + " result(s).");
                if (adList.size() > 0) ad = (AccountDeprovisioning)adList.get(0);
            } catch (ProviderException pe) {
                // There was an error querying the AccountDeprovisioning service
                String errType = "application";
                String errCode = "AwsAccountService-100X";
                String errDesc = "An error occurred querying the AccountDeprovisioning "
                		+ "provider to verify the baseline state of the AccountDeprovisioning. "
                		+ "The exception is: " + pe.getMessage();
                logger.error(LOGTAG + errDesc);
                logger.error("Message sent in is: \n" + getMessageBody(inDoc));
                ArrayList errors = new ArrayList();
                errors.add(buildError(errType, errCode, errDesc));
                String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
                return getMessage(msg, replyContents);
            }

            if (ad != null) {
                // Compare the retrieved baseline with the baseline in the
                // update request message.
                try {
                    if (baselineAd.equals(ad)) {
                        logger.info(LOGTAG + "Baseline matches the current " +
                        	"state of the AccountDeprovisioning in the AWS Account Service.");
                    } else {
                        logger.info(LOGTAG + "Baseline does not match the " +
                        	"current state of the AccountDeprovisioning in the AWS Account " +
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
                // Respond with an error, because no VPCP matching the
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
                if (baselineAd.equals(newAd)) {
                    String errType = "application";
                    String errCode = "AwsAccountService-100X";
                    String errDesc = "Baseline state and new state of the "
                            + "object are equal. No update operation may be performed.";
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

            // Update the AccountDeprovisioning object using the provider.
            try {
                long updateStartTime = System.currentTimeMillis();
                logger.info(LOGTAG + "Updating the AccountDeprovisioning object in the provider...");
                getProvider().update(newAd);
                long updateTime = System.currentTimeMillis() - updateStartTime;
                logger.info(LOGTAG + "AccountDeprovisioning update processed by provider in "
                		+ updateTime + " ms.");
            } catch (ProviderException pe) {
                // There was an error updating the AccountDeprovisioning
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
            logger.info(LOGTAG + "Updated AccountDeprovisioning: " + newAd.toString());

            // Set the baseline on the new state of the AccountDeprovisioning.
            newAd.setBaseline(baselineAd);

            // Publish an Update-Sync Message
            try {
                MessageProducer producer = getProducerPool().getProducer();
                long publishStartTime = System.currentTimeMillis();
                if (getVerbose())
                    logger.info(LOGTAG + "Publishing AccountDeprovisioning.Update-Sync message...");
                newAd.updateSync((SyncService) producer);
                long publishTime = System.currentTimeMillis() - publishStartTime;
                logger.info(LOGTAG + "Published AccountDeprovisioning.Update-Sync message in "
                        + publishTime + " ms.");
            } catch (EnterpriseObjectSyncException eose) {
                String errMsg = "An error occurred publishing the Account" +
                		"Deprovisinoing.Update-Sync message after updating the"
                		+ " the AccountDeprovisioning object. The exception is: " +
                		eose.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new CommandException(errMsg, eose);
            } catch (JMSException jmse) {
                String errMsg = "An error occurred publishing the " +
                	"AccountDeprovisioning.Update-Sync " +
                	"message after generating an identity. The "
                     + "exception is: " + jmse.getMessage();
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
            logger.info(LOGTAG + "Update-Request command execution complete in " + executionTime + " ms.");

            // Return the response with status success.
            return getMessage(msg, replyContents);
        }

        // Handle a Delete-Request.
        if (msgAction.equalsIgnoreCase("Delete")) {
            logger.info(LOGTAG + "Handling a com.amazon.aws.Provisioning." +
            	"AccountDeprovisioning.Delete-Request message.");
            Element eAccountDeprovisioning = inDoc.getRootElement().getChild("DataArea")
            	.getChild("DeleteData").getChild("AccountDeprovisioning");

            // Verify that the AccountDeprovisioning element is not null. If it is
            // null, reply with an error.
            if (eAccountDeprovisioning == null) {
                String errType = "application";
                String errCode = "OpenEAI-1015";
                String errDesc = "Invalid element found in the Delete-Request "
                        + "message. This command expects an AccountDeprovisioning";
                logger.error(LOGTAG + errDesc);
                logger.error("Message sent in is: \n" + getMessageBody(inDoc));
                ArrayList errors = new ArrayList();
                errors.add(buildError(errType, errCode, errDesc));
                String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
                return getMessage(msg, replyContents);
            }

            // Get a configured AccountDeprovisioning from AppConfig.
            AccountDeprovisioning ad = new AccountDeprovisioning();
            try {
                ad = (AccountDeprovisioning) getAppConfig()
                	.getObjectByType(ad.getClass().getName());
            }
            catch (EnterpriseConfigurationObjectException eoce) {
                String errMsg = "Error retrieving an object from AppConfig: " +
                	"The exception" + "is: " + eoce.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new CommandException(errMsg);
            }

            // Now build an AccountDeprovisioning object from the element in the
            // message.
            try {
                ad.buildObjectFromInput(eAccountDeprovisioning);
                if (eTestId != null) testId.buildObjectFromInput(eTestId);
                ad.setTestId(testId);
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

            // Delete the AccountDeprovisioning object using the provider.
            logger.info(LOGTAG + "Deleting an AccountDeprovisioning object...");

            try {
            	long deleteStartTime = System.currentTimeMillis();
                getProvider().delete(ad);
                long deleteTime = System.currentTimeMillis() - deleteStartTime;
                logger.info(LOGTAG + "Deleted AccountDeprovisioning in " + deleteTime + " ms.");
            }
            catch (ProviderException pe) {
                // There was an error deleting the AccountDeprovisioning
                String errType = "application";
                String errCode = "AwsAccountService-100X";
                String errDesc = "An error occurred deleting the Account" +
                	"Deprovisioning object. The " + "exception is: " + pe.getMessage();
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
                    logger.info(LOGTAG + "Publishing AccountDeprovisioning.Delete-Sync message...");
                ad.deleteSync("delete", (SyncService) producer);
                logger.info(LOGTAG + "Published AccountDeprovisioning.Delete-Sync" + " message.");
            } catch (EnterpriseObjectSyncException eose) {
                String errMsg = "An error occurred publishing the Account"
                        + "Deprovisinoing.Delete-Sync message after deleting "
                		+ "the AccountDeprovisioning object. The " + "exception is: "
                        + eose.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new CommandException(errMsg, eose);
            } catch (JMSException jmse) {
            	String errMsg = "An error occurred publishing the Account"
                        + "Deprovisioning.Delete-Sync message after deleting "
                		+ "the AccountDeprovisioning. The " + "exception is: "
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
                    + "This command only supports query, generate, create, update, and delete.";
            logger.fatal(LOGTAG + errDesc);
            logger.fatal("Message sent in is: \n" + getMessageBody(inDoc));
            ArrayList errors = new ArrayList();
            errors.add(buildError(errType, errCode, errDesc));
            String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
            return getMessage(msg, replyContents);
        }
    }

    /**
     * @param AccountDeprovisioningProvider
     *            , the account deprovisinoing provider
     *            <P>
     *            Sets the account deprovisioning provider for this command.
     */
    protected void setProvider(AccountDeprovisioningProvider provider) {
        m_provider = provider;
    }

    /**
     * @return AccountDeprovisioningProvider, the AccountDeprovisioning provider
     *         <P>
     *         Gets the AccountDeprovisioning provider for this command.
     */
    protected AccountDeprovisioningProvider getProvider() {
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
