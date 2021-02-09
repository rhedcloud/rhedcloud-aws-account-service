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
import com.amazon.aws.moa.objects.resources.v1_0.VirtualPrivateCloudRequisition;
import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.VirtualPrivateCloudProvisioning;
import com.amazon.aws.moa.objects.resources.v1_0.VirtualPrivateCloudProvisioningQuerySpecification;

// VPC Provider Implementation
import edu.emory.awsaccount.service.provider.VirtualPrivateCloudProvisioningProvider;
import edu.emory.awsaccount.service.provider.ProviderException;

//Apache Commons Validators
import org.apache.commons.validator.GenericValidator;
import org.apache.commons.validator.routines.InetAddressValidator;

/**
 * This command handles requests for VirtualPrivateCloudProvisioning objects.
 * Specifically, it handles a Generate-Request. All other actions for the
 * VirtualPrivateCloudProvisioning object are handled by a deployment of the
 * RDBMS connector for persistence and retrieval purposes only. This command
 * also proxies query requests to the RDBMS connector implementation, so
 * one command and one endpoint can cleanly implement the entire public
 * interface for virtual private cloud provisioning.
 * <P>
 * The generate and query actions for VirtualPrivateCloudProvisioning are
 * invoked by clients wanting to provision a new Emory AWS
 * VirtualPrivateCloud in an Emory AWS account. The generate operation
 * passes in a VirtualPrivateCloudRequisition object with basic parameters
 * for the VPC they are requesting. The generate action immediately returns
 * a VirtualPrivateCloudProvisioning object with detailed status of the
 * provisioning process and a ProvisioningId that can be used for subsequent
 * queries for updates on the progress of provisioning. Additionally, like
 * all ESB services the AwsAccountService also publishes create, update, and
 * delete sync messages, so as a new instance of the provisioning process
 * is created and updated, create and update sync messages are published.
 * Applications interested in the status of provisioning may also consume
 * these messages to take action on provisioning operations.
 * <OL>
 * <LI>com.amazon.aws.Provisioning.VirtualPrivateCloud.Query-Request (<A HREF=
 * "https://svn.service.emory.edu:8443/cgi-bin/viewvc.cgi/emoryoit/message/releases/com/amazon/aws/Provisioning/VirtualPrivateCloudProvisioning/1.0/dtd/Query-Request.dtd?view=markup"
 * >Definition</A> | <A HREF=
 * "https://svn.service.emory.edu:8443/cgi-bin/viewvc.cgi/emoryoit/message/releases/com/amazon/aws/Provisioning/VirualPrivateCloudProvisioning/1.0/xml/Query-Request.xml?view=markup"
 * >Sample Message</A>)
 * <UL>
 * <LI>Convert the JMS message to and XML document</LI>
 * <LI>Build a message object from the XML document for the
 * VirtualPrivateCloudProvisioningQuerySpecification</LI>
 * <LI>Get a configured VirtualPrivateCloudProvisioning object from
 * AppConfig</LI>
 * <LI>Get the P2P producer pool configured to send messages to the
 * AwsAccountService RDBMS command implementation</LI>
 * <LI>Call the query method on the VirtualPrivateCloudProvisioiningObject</LI>
 * <LI>Proxy the result to the requestor by building the message response from
 * the results of the query operation. This should contain a list of
 * zero or more VirtualPrivateCloudProvisioning objects.</LI>
 * <LI>Return the response.</LI>
 * </UL>
 * </LI>
 * <LI>com.amazon.aws.Provisioning.VirtualPrivateCloud.Generate-Request (<A
 * HREF=
 * "https://svn.service.emory.edu:8443/cgi-bin/viewvc.cgi/emoryoit/message/releases/com/amazon/aws/Provisioning/VirtualPrivateCloudProvisioning/1.0/dtd/Generate-Request.dtd?view=markup"
 * >Definition</A> | <A HREF=
 * "https://svn.service.emory.edu:8443/cgi-bin/viewvc.cgi/emoryoit/message/releases/com/amazon/aws/Provisioning/VirtualPrivateCloudProvisioning/1.0/xml/Generate-Request.xml?view=markup"
 * >Sample Message</A>)
 * <UL>
 * <LI>Convert the JMS message to and XML document</LI>
 * <LI>Build a message object from the XML document for the
 * VirtualPrivateCloudRequisition object</LI>
 * <LI>Invoke the generate method of the
 * configured virtual private cloud provisioning provider. </LI>
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
public class VirtualPrivateCloudProvisioningRequestCommand extends AwsAccountRequestCommand implements RequestCommand {
    private static String LOGTAG = "[VirtualPrivateCloudProvisioningRequestCommand] ";
    private static Logger logger = Logger.getLogger(VirtualPrivateCloudProvisioningRequestCommand.class);
    private VirtualPrivateCloudProvisioningProvider m_provider = null;
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
    public VirtualPrivateCloudProvisioningRequestCommand(CommandConfig cConfig) throws InstantiationException {
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

        // Initialize a VirtualPrivateCloudProvisioningProvider
        String className = getProperties()
        	.getProperty("virtualPrivateCloudProvisioningProviderClassName");
        if (className == null || className.equals("")) {
            String errMsg = "No virtualPrivateCloudProvisioningProviderClassName property "
                    + "specified. Can't continue.";
            logger.fatal(LOGTAG + errMsg);
            throw new InstantiationException(errMsg);
        }
        logger.info(LOGTAG + "virtualPrivateCloudProvisioningProviderClassName" +
        	"is: " + className);

        VirtualPrivateCloudProvisioningProvider provider = null;
        try {
            logger.info(LOGTAG + "Getting class for name: " + className);
            Class providerClass = Class.forName(className);
            if (providerClass == null)
                logger.info(LOGTAG + "providerClass is null.");
            else
                logger.info(LOGTAG + "providerClass is not null.");
            provider = (VirtualPrivateCloudProvisioningProvider) Class.forName(className).newInstance();
            logger.info(LOGTAG + "Initializing VirtualPrivateCloudProvisioningProvider: "
                    + provider.getClass().getName());
            provider.init(getAppConfig());
            logger.info(LOGTAG + "VirtualPrivateCloudProvisioningProvider initialized.");
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
            String errMsg = "An error occurred initializing the " + "VirtualPrivateCloudProvisioningProvider " + className
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
        // Get a configured VirtualPrivateCloudProvisioning from AppConfig.
        VirtualPrivateCloudProvisioning vpcp = new VirtualPrivateCloudProvisioning();
        try {
            vpcp = (VirtualPrivateCloudProvisioning) getAppConfig()
            	.getObjectByType(vpcp.getClass().getName());
        } catch (EnterpriseConfigurationObjectException eoce) {
            String errMsg = "Error retrieving an object from AppConfig: The exception" + "is: " + eoce.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new InstantiationException(errMsg);
        }

        // Get a VirtualPrivateCloudRequisition from AppConfig.
        VirtualPrivateCloudRequisition req = new VirtualPrivateCloudRequisition();
        try {
            req = (VirtualPrivateCloudRequisition) getAppConfig()
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
        if (msgObject.equalsIgnoreCase("VirtualPrivateCloudProvisioning") == false) {
            String errType = "application";
            String errCode = "OpenEAI-1001";
            String errDesc = "Unsupported message object: " + msgObject
                    + ". This command expects 'VirtualPrivateCloudProvisioning'.";
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

        // Get a configured VirtualPrivateCloudRequisition from AppConfig.
        VirtualPrivateCloudRequisition req = new VirtualPrivateCloudRequisition();
        TestId testId = new TestId();
        try {
            req = (VirtualPrivateCloudRequisition) getAppConfig().getObjectByType(req.getClass().getName());
            testId = (TestId) getAppConfig().getObjectByType(testId.getClass().getName());
        } catch (EnterpriseConfigurationObjectException eoce) {
            String errMsg = "Error retrieving an object from AppConfig: The exception" + "is: " + eoce.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new CommandException(errMsg, eoce);
        }
        Element eTestId = inDoc.getRootElement().getChild("ControlAreaRequest").getChild("Sender").getChild("TestId");

        // Handle a Generate-Request.
        if (msgAction.equalsIgnoreCase("Generate")) {
            logger.info(LOGTAG + "Handling a com.amazon.aws.Provisioning.VirtualPrivateCloudProvisioning.Generate-Request"
                    + " message.");
            Element eGenerateObject = inDoc.getRootElement().getChild("DataArea").getChild("VirtualPrivateCloudRequisition");

            // Verify that the generate object element is not null. If it is
            // null, reply
            // with an error.
            if (eGenerateObject == null) {
                String errType = "application";
                String errCode = "OpenEAI-1015";
                String errDesc = "Invalid element found in the Generate-Request "
                        + "message. This command expects a VirtualPrivateCloudRequisition";
                logger.error(LOGTAG + errDesc);
                logger.error("Message sent in is: \n" + getMessageBody(inDoc));
                ArrayList errors = new ArrayList();
                errors.add(buildError(errType, errCode, errDesc));
                String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
                return getMessage(msg, replyContents);
            }

            // Now build a VirtualPrivateCloudRequisition object from the element in the message.
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

            // Generate the VirtualPrivateCloudProvisioning object using the provider implementation.
            logger.info(LOGTAG + "Generating a VirtualPrivateCloudProvisioning object...");

            VirtualPrivateCloudProvisioning vpcp = null;
            try {
            	long generateStartTime = System.currentTimeMillis();
                vpcp = getProvider().generate(req);
                long generateTime = System.currentTimeMillis() - generateStartTime;
                if (getVerbose()) logger.info(LOGTAG + "Generated VPCP in " + generateTime + " ms." );
                if (eTestId != null)
                    vpcp.setTestId(testId);
            } catch (Throwable pe) {
                logger.error(LOGTAG, pe);
                // There was an error generating the identity
                String errType = "application";
                String errCode = "AwsAccountService-1003";
                String errDesc = "An error occurred generating the VPCP. The " + "exception is: " + pe.getMessage();
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
                    logger.info(LOGTAG + "Publishing VirtualPrivateCloudProvisioning.Create-Sync message...");
                Authentication auth = new Authentication();
                auth.setAuthUserId(authUserId);
                auth.setAuthUserSignature("none");
                vpcp.setAuthentication(auth);
                vpcp.createSync((SyncService) producer);
                logger.info(LOGTAG + "Published VirtualPrivateCloudProvisioning.Create-Sync" + " message.");
            } catch (EnterpriseObjectSyncException eose) {
                String errMsg = "An error occurred publishing the MasterPatientParticipant"
                        + "Identity.Create-Sync message after generating an identity. The " + "exception is: "
                        + eose.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new CommandException(errMsg, eose);
            } catch (JMSException jmse) {
                String errMsg = "An error occurred publishing the MasterPatientParticipant"
                        + "Identity.Create-Sync message after generating an identity. The " + "exception is: "
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
                eVpcp = (Element) vpcp.buildOutputFromObject();
            } catch (EnterpriseLayoutException ele) {
                String errMsg = "An error occurred serializing a VirtualPrivateCloudProvisioning object to an XML element. The exception is: "
                        + ele.getMessage();
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
            logger.info(LOGTAG + "Handling an com.amazon.aws.Provisioning.VirtualPrivataeCloudProvisioning."
                    + "Query-Request message.");
            Element eQuerySpec = inDoc.getRootElement().getChild("DataArea")
                    .getChild("VirtualPrivateCloudProvisioningQuerySpecification");

            // Get a configured query object from AppConfig.
            VirtualPrivateCloudProvisioningQuerySpecification querySpec = new VirtualPrivateCloudProvisioningQuerySpecification();
            try {
                querySpec = (VirtualPrivateCloudProvisioningQuerySpecification) getAppConfig().getObjectByType(
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

            // Query for the VirtualPrivateCloudProvisioning from the provider.
            logger.info(LOGTAG + "Querying for the VirtualPrivateCloudProvisioning...");

            List results = null;
            try {
            	long queryStartTime = System.currentTimeMillis();
                results = getProvider().query(querySpec);
                long queryTime = System.currentTimeMillis() - queryStartTime;
                logger.info(LOGTAG + "Queried for VirtualPrivateCloudProvisioning in " + queryTime + "ms.");
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
            	logger.info(LOGTAG + "Results are null; no matching VPC found.");
            }

            // Prepare the response.
            localProvideDoc.getRootElement().getChild("DataArea").removeContent();
            // If there are results, place them in the response.
            if (results != null && results.size() > 0) {

                ArrayList mppiList = new ArrayList();
                for (int i = 0; i < results.size(); i++) {
                    Element eVpc = null;
                    try {
                        VirtualPrivateCloudProvisioning vpcp = (VirtualPrivateCloudProvisioning) results.get(i);
                        eVpc = (Element) vpcp.buildOutputFromObject();
                        mppiList.add(eVpc);
                        if (eTestId != null)
                            vpcp.setTestId(testId);
                    } catch (EnterpriseLayoutException ele) {
                        String errMsg = "An error occurred serializing "
                                + "VirtualPrivateCloudProvisioning object "
                        		+ "to an XML element. The exception is: "
                                + ele.getMessage();
                        logger.error(LOGTAG + errMsg);
                        throw new CommandException(errMsg, ele);
                    }
                }
                localProvideDoc.getRootElement().getChild("DataArea").addContent(mppiList);
            }
            String replyContents = buildReplyDocument(eControlArea, localProvideDoc);

            // Return the response with status success.
            return getMessage(msg, replyContents);
        }

        // Handle a Create-Request.
        if (msgAction.equalsIgnoreCase("Create")) {
            logger.info(LOGTAG + "Handling a com.amazon.aws.Provisioning." +
            	"VirtualPrivateProvisioningCloud.Create-Request message.");
            Element eVpcp = inDoc.getRootElement().getChild("DataArea")
            	.getChild("NewData").getChild("VirtualPrivateCloudProvisioning");

            // Verify that the VirtualPrivateCloudProvisioning element is not null. If it is
            // null, reply with an error.
            if (eVpcp == null) {
                String errType = "application";
                String errCode = "OpenEAI-1015";
                String errDesc = "Invalid element found in the Create-Request "
                        + "message. This command expects a VirtualPrivateCloudProvisioning";
                logger.error(LOGTAG + errDesc);
                logger.error("Message sent in is: \n" + getMessageBody(inDoc));
                ArrayList errors = new ArrayList();
                errors.add(buildError(errType, errCode, errDesc));
                String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
                return getMessage(msg, replyContents);
            }

            // Get a configured VirtualPrivateCloudProvisioning from AppConfig.
            VirtualPrivateCloudProvisioning vpcp = new VirtualPrivateCloudProvisioning();
            try {
                vpcp = (VirtualPrivateCloudProvisioning) getAppConfig()
                	.getObjectByType(vpcp.getClass().getName());
            } catch (EnterpriseConfigurationObjectException eoce) {
                String errMsg = "Error retrieving an object from AppConfig: The exception" + "is: " + eoce.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new CommandException(errMsg);
            }

            // Now build a virtual private cloud provisioning object from the element in the
            // message.
            try {
                vpcp.buildObjectFromInput(eVpcp);
                if (eTestId != null) {
                	testId.buildObjectFromInput(eTestId);
                	vpcp.setTestId(testId);
                }
                logger.info(LOGTAG + "TestId is: " + vpcp.getTestId().toString());
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

            // Create the VPC object using the provider.
            logger.info(LOGTAG + "Creating a VPCP...");

            try {
            	long createStartTime = System.currentTimeMillis();
                getProvider().create(vpcp);
                long createTime = System.currentTimeMillis() - createStartTime;
                logger.info(LOGTAG + "Created VirtualPrivateCloudProvisioning in " + createTime + " ms.");
            }
            catch (ProviderException pe) {
                // There was an error creating the VPC
                String errType = "application";
                String errCode = "AwsAccountService-100X";
                String errDesc = "An error occurred deleting the Virtual" +
                	"PrivateCloud. The " + "exception is: " + pe.getMessage();
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
                    logger.info(LOGTAG + "Publishing VirtualPrivateCloudProvisioning.Create-Sync message...");
                vpcp.createSync((SyncService) producer);
                logger.info(LOGTAG + "Published VirtualPrivateCloudProvisioning.Create-Sync" + " message.");
            } catch (EnterpriseObjectSyncException eose) {
                String errMsg = "An error occurred publishing the Virtual"
                        + "PrivateCloud.Create-Sync message after creating "
                		+ "the VPC. The " + "exception is: "
                        + eose.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new CommandException(errMsg, eose);
            } catch (JMSException jmse) {
            	String errMsg = "An error occurred publishing the Virtual"
                        + "PrivateCloud.Create-Sync message after creating "
                		+ "the VPC. The " + "exception is: "
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
            		"VirtualPrivateCloudProvisioning.Update-Request message.");

            // Verify that the baseline is not null.
            Element eBaselineData = inDoc.getRootElement().getChild("DataArea").getChild("BaselineData")
                    .getChild("VirtualPrivateCloudProvisioning");
            Element eNewData = inDoc.getRootElement().getChild("DataArea").getChild("NewData")
                    .getChild("VirtualPrivateCloudProvisioning");
            if (eNewData == null || eBaselineData == null) {
                String errMsg = "Either the baseline or new data state of the "
                        + "VirtualPrivateCloudProvisioning is null. Can't continue.";
                throw new CommandException(errMsg);
            }

            // Get configured objects from AppConfig.
            VirtualPrivateCloudProvisioning baselineVpcp = new VirtualPrivateCloudProvisioning();
            VirtualPrivateCloudProvisioning newVpcp = new VirtualPrivateCloudProvisioning();
            try {
                baselineVpcp = (VirtualPrivateCloudProvisioning) getAppConfig()
                	.getObjectByType(baselineVpcp.getClass().getName());
                newVpcp = (VirtualPrivateCloudProvisioning) getAppConfig()
                	.getObjectByType(newVpcp.getClass().getName());
            }
            catch (EnterpriseConfigurationObjectException ecoe) {
                String errMsg = "An error occurred retrieving an object from "
                	+ "AppConfig. The exception is: " + ecoe.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new CommandException(errMsg, ecoe);
            }
            if (eTestId != null)
                newVpcp.setTestId(testId);

            // Build the baseline and newdata states of the VPCP.
            try {
                baselineVpcp.buildObjectFromInput(eBaselineData);
                newVpcp.buildObjectFromInput(eNewData);
                if (eTestId != null) {
                	testId.buildObjectFromInput(eTestId);
                	newVpcp.setTestId(testId);
                }
                logger.info(LOGTAG + "TestId is: " + newVpcp.getTestId().toString());
            } catch (EnterpriseLayoutException ele) {
                String errMsg = "An error occurred building the baseline and newdata"
                        + " states of the VPCP object passed in. "
                		+ "The exception is: " + ele.getMessage();
                throw new CommandException(errMsg, ele);
            }

            // Perform the baseline check.

            // Get a configured VirtualPrivateCloudProvisioningQuerySpecification from
            // AppConfig.
            VirtualPrivateCloudProvisioningQuerySpecification querySpec =
            	new VirtualPrivateCloudProvisioningQuerySpecification();
            try {
                querySpec = (VirtualPrivateCloudProvisioningQuerySpecification) getAppConfig()
                	.getObjectByType(querySpec.getClass().getName());
            } catch (EnterpriseConfigurationObjectException eoce) {
                String errMsg = "Error retrieving an object from AppConfig: "
                	+ "The exception" + "is: " + eoce.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new CommandException(errMsg);
            }

            // Set the value of the VpcId.
            try {
            	querySpec.setProvisioningId(baselineVpcp.getProvisioningId());
            }
            catch (EnterpriseFieldException efe) {
            	String errMsg = "An error occurred setting the field values " +
            		"of the VPCP query specification. The exception is: " +
            		efe.getMessage();
            	logger.error(LOGTAG + errMsg);
            	throw new CommandException(errMsg, efe);
            }

            // Query for the VPCP.
            VirtualPrivateCloudProvisioning vpcp = null;
            try {
            	logger.info(LOGTAG + "Querying for baseline VPCP...");
                List vpcpList = getProvider().query(querySpec);
                logger.info(LOGTAG + "Found " + vpcpList.size() + " result(s).");
                if (vpcpList.size() > 0) vpcp = (VirtualPrivateCloudProvisioning)vpcpList.get(0);
            } catch (ProviderException pe) {
                // There was an error querying the VPCP service
                String errType = "application";
                String errCode = "AmazonAccountService-100X";
                String errDesc = "An error occurred querying the VPC provider"
                		+ "to verify the baseline state of the VPC. "
                		+ "The exception is: " + pe.getMessage();
                logger.error(LOGTAG + errDesc);
                logger.error("Message sent in is: \n" + getMessageBody(inDoc));
                ArrayList errors = new ArrayList();
                errors.add(buildError(errType, errCode, errDesc));
                String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
                return getMessage(msg, replyContents);
            }

            if (vpcp != null) {
                // Compare the retrieved baseline with the baseline in the
                // update request message.
                try {
                    if (baselineVpcp.equals(vpcp)) {
                        logger.info(LOGTAG + "Baseline matches the current " +
                        	"state of the VPCP in the AWS Account Service.");
                    } else {
                        logger.info(LOGTAG + "Baseline does not match the " +
                        	"current state of the VPCP in the AWS Account " +
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
                if (baselineVpcp.equals(newVpcp)) {
                    String errType = "application";
                    String errCode = "AwsAccountService-100X";
                    String errDesc = "Baseline state and new state of the "
                            + "identity are equal. No update operation may be performed.";
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

            // Update the VirtualPrivateCloudProvider object using the VPCP provider.
            try {
                long updateStartTime = System.currentTimeMillis();
                logger.info(LOGTAG + "Updating the VPCP in the VpcpProvider...");
                getProvider().update(newVpcp);
                long updateTime = System.currentTimeMillis() - updateStartTime;
                logger.info(LOGTAG + "VPCP update processed by VpcpProvider in "
                		+ updateTime + " ms.");
            } catch (ProviderException pe) {
                // There was an error updating the VPC
                String errType = "application";
                String errCode = "MppiService-1002";
                String errDesc = "An error occurred updating the object. The " + "exception is: " + pe.getMessage();
                logger.error(LOGTAG + errDesc);
                logger.error("Message sent in is: \n" + getMessageBody(inDoc));
                ArrayList errors = new ArrayList();
                errors.add(buildError(errType, errCode, errDesc));
                String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
                return getMessage(msg, replyContents);
            }
            logger.info(LOGTAG + "Updated VPCP: " + newVpcp.toString());

            // Set the baseline on the new state of the identity.
            newVpcp.setBaseline(baselineVpcp);

            // Publish an Update-Sync Message
            try {
                MessageProducer producer = getProducerPool().getProducer();
                long publishStartTime = System.currentTimeMillis();
                if (getVerbose())
                    logger.info(LOGTAG + "Publishing VirtualPrivateCloudProvisioning.Update-Sync message...");
                newVpcp.updateSync((SyncService) producer);
                long publishTime = System.currentTimeMillis() - publishStartTime;
                logger.info(LOGTAG + "Published VirtualPrivateCloudProvisioning.Update-Sync message in "
                        + publishTime + " ms.");
            } catch (EnterpriseObjectSyncException eose) {
                String errMsg = "An error occurred publishing the Virtual" +
                		"PrivateCloudProvisinoing.Update-Sync message after updating the"
                		+ " the VPC. The exception is: " + eose.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new CommandException(errMsg, eose);
            } catch (JMSException jmse) {
                String errMsg = "An error occurred publishing the VirtualPrivateCloudProvisioning.Update-Sync " +
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
            	"VirtualPrivateCloudProvisioning.Delete-Request message.");
            Element eVpc = inDoc.getRootElement().getChild("DataArea")
            	.getChild("DeleteData").getChild("VirtualPrivateCloudProvisioning");

            // Verify that the VirtualPrivateCloudProvisioning element is not null. If it is
            // null, reply with an error.
            if (eVpc == null) {
                String errType = "application";
                String errCode = "OpenEAI-1015";
                String errDesc = "Invalid element found in the Delete-Request "
                        + "message. This command expects a VirtualPrivateCloudProvisioning";
                logger.error(LOGTAG + errDesc);
                logger.error("Message sent in is: \n" + getMessageBody(inDoc));
                ArrayList errors = new ArrayList();
                errors.add(buildError(errType, errCode, errDesc));
                String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
                return getMessage(msg, replyContents);
            }

            // Get a configured VirtualPrivateCloudProvisioning from AppConfig.
            VirtualPrivateCloudProvisioning vpcp = new VirtualPrivateCloudProvisioning();
            try {
                vpcp = (VirtualPrivateCloudProvisioning) getAppConfig()
                	.getObjectByType(vpcp.getClass().getName());
            } catch (EnterpriseConfigurationObjectException eoce) {
                String errMsg = "Error retrieving an object from AppConfig: The exception" + "is: " + eoce.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new CommandException(errMsg);
            }

            // Now build a virtual private cloud provisioning object from the element in the
            // message.
            try {
                vpcp.buildObjectFromInput(eVpc);
                if (eTestId != null) testId.buildObjectFromInput(eTestId);
                vpcp.setTestId(testId);
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

            // Delete the VPCP object using the provider.
            logger.info(LOGTAG + "Deleting a VPCP...");

            try {
            	long deleteStartTime = System.currentTimeMillis();
                getProvider().delete(vpcp);
                long deleteTime = System.currentTimeMillis() - deleteStartTime;
                logger.info(LOGTAG + "Deleted VirtualPrivateCloudProvisioning in " + deleteTime + " ms.");
            }
            catch (ProviderException pe) {
                // There was an error deleting the VPC
                String errType = "application";
                String errCode = "AwsAccountService-100X";
                String errDesc = "An error occurred deleting the Virtual" +
                	"PrivateCloudProvisioning object. The " + "exception is: " + pe.getMessage();
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
                    logger.info(LOGTAG + "Publishing VirtualPrivateCloudProvisioning.Delete-Sync message...");
                vpcp.deleteSync("delete", (SyncService) producer);
                logger.info(LOGTAG + "Published VirtualPrivateCloudProvisioning.Delete-Sync" + " message.");
            } catch (EnterpriseObjectSyncException eose) {
                String errMsg = "An error occurred publishing the Virtual"
                        + "PrivateCloudProvisinoing.Delete-Sync message after deleting "
                		+ "the VPC. The " + "exception is: "
                        + eose.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new CommandException(errMsg, eose);
            } catch (JMSException jmse) {
            	String errMsg = "An error occurred publishing the Virtual"
                        + "PrivateCloudProvisinoing.Delete-Sync message after deleting "
                		+ "the VPC. The " + "exception is: "
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
     * @param VirtualPrivateCloudProvisioningProvider
     *            , the VPC provider
     *            <P>
     *            Sets the VPC provider for this command.
     */
    protected void setProvider(VirtualPrivateCloudProvisioningProvider provider) {
        m_provider = provider;
    }

    /**
     * @return VirtualPrivateCloudProvisioningProvider, the VPC provider
     *         <P>
     *         Gets the VPC provider for this command.
     */
    protected VirtualPrivateCloudProvisioningProvider getProvider() {
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
