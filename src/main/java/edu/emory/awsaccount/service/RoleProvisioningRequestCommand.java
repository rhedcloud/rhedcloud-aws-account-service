/* *****************************************************************************
 This file is part of the RHEDcloud AWS Account Service.

 Copyright 2020 RHEDcloud Foundation. All rights reserved.
 ******************************************************************************/

package edu.emory.awsaccount.service;

import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.RoleProvisioning;
import com.amazon.aws.moa.objects.resources.v1_0.RoleProvisioningQuerySpecification;
import com.amazon.aws.moa.objects.resources.v1_0.RoleProvisioningRequisition;
import edu.emory.awsaccount.service.provider.ProviderException;
import edu.emory.awsaccount.service.provider.RoleProvisioningProvider;
import org.apache.commons.validator.GenericValidator;
import org.apache.commons.validator.routines.InetAddressValidator;
import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;
import org.jdom.Document;
import org.jdom.Element;
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
import org.openeai.moa.objects.resources.Error;
import org.openeai.moa.objects.testsuite.TestId;
import org.openeai.transport.SyncService;

import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.TextMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;

/**
 * This command handles requests for RoleProvisioning objects.
 * Specifically, it handles a Generate-Request. All other actions for the
 * RoleProvisioning object are handled by a deployment of the
 * RDBMS connector for persistence and retrieval purposes only. This command
 * also proxies query requests to the RDBMS connector implementation, so
 * one command and one endpoint can cleanly implement the entire public
 * interface for role provisioning.
 * <P>
 * The generate and query actions for RoleProvisioning are
 * invoked by clients wanting to provision custom roles in an existing AWS account.
 * The generate operation passes in an RoleProvisioningRequisition object
 * with the account number and role information to provision. The generate action immediately returns
 * a RoleProvisioning object with detailed status of the
 * provisioning process and a RoleProvisioningId that can be used for subsequent
 * queries for updates on the progress of provisioning. Additionally, like
 * all similar services the AwsAccountService also publishes create, update, and
 * delete sync messages, so as a new instance of the provisioning process
 * is created and updated, create and update sync messages are published.
 * Applications interested in the status of provisioning may also consume
 * these messages to take action on provisioning operations.
 * <OL>
 * <LI>com.amazon.aws.Provisioning.RoleProvisioning.Query-Request (<A HREF=
 * "https://bitbucket.org/rhedcloud/rhedcloud-aws-moas/src/master/message/releases/com/amazon/aws/Provisioning/RoleProvisioning/1.0/dtd/Query-Request.dtd"
 * >Definition</A> | <A HREF=
 * "https://bitbucket.org/rhedcloud/rhedcloud-aws-moas/src/master/message/releases/com/amazon/aws/Provisioning/RoleProvisioning/1.0/xml/Query-Request.xml"
 * >Sample Message</A>)
 * <UL>
 * <LI>Convert the JMS message to and XML document</LI>
 * <LI>Build a message object from the XML document for the
 * RoleProvisioningQuerySpecification</LI>
 * <LI>Get a configured RoleProvisioning object from
 * AppConfig</LI>
 * <LI>Get the P2P producer pool configured to send messages to the
 * AwsAccountService RDBMS command implementation</LI>
 * <LI>Call the query method on the RoleProvisioning</LI>
 * <LI>Proxy the result to the requestor by building the message response from
 * the results of the query operation. This should contain a list of
 * zero or more RoleProvisioning objects.</LI>
 * <LI>Return the response.</LI>
 * </UL>
 * </LI>
 * <LI>com.amazon.aws.Provisioning.RoleProvisioning.Generate-Request (<A
 * HREF=
 * "https://bitbucket.org/rhedcloud/rhedcloud-aws-moas/src/master/message/releases/com/amazon/aws/Provisioning/RoleProvisioning/1.0/dtd/Generate-Request.dtd"
 * >Definition</A> | <A HREF=
 * "https://bitbucket.org/rhedcloud/rhedcloud-aws-moas/src/master/message/releases/com/amazon/aws/Provisioning/RoleProvisioning/1.0/xml/Generate-Request.xml"
 * >Sample Message</A>)
 * <UL>
 * <LI>Convert the JMS message to and XML document</LI>
 * <LI>Build a message object from the XML document for the
 * RoleProvisioning object</LI>
 * <LI>Invoke the generate method of the
 * configured role provisioning provider. </LI>
 * <LI>Build the response to the request message</LI>
 * <LI>Return the response to the request message</LI>
 * </UL>
 * </LI>
 *
 * </OL>
 */
public class RoleProvisioningRequestCommand extends AwsAccountRequestCommand implements RequestCommand {
    private static final String LOGTAG = "[RoleProvisioningRequestCommand] ";
    private static final Logger logger = Logger.getLogger(RoleProvisioningRequestCommand.class);
    private RoleProvisioningProvider m_provider = null;
    private ProducerPool m_producerPool = null;

    /**
     * This constructor initializes the command using a
     * CommandConfig object. It invokes the constructor of the
     * ancestor, RequestCommandImpl, and then retrieves one
     * PropertyConfig object from AppConfig by name and gets and
     * sets the command properties using that PropertyConfig object.
     * This means that this command must have one PropertyConfig
     * object in its configuration named 'GeneralProperties'. This
     * constructor also initializes the response document and
     * provide document used in replies.
     *
     * @param cConfig command config
     * @throws InstantiationException on error
     */
    public RoleProvisioningRequestCommand(CommandConfig cConfig) throws InstantiationException {
        super(cConfig);
        logger.info(LOGTAG + "Initializing " + ReleaseTag.getReleaseInfo());

        // Initialize a command-specific logger if it exists.
        try {
            LoggerConfig lConfig = (LoggerConfig) getAppConfig().getObjectByType(LoggerConfig.class.getName());
            PropertyConfigurator.configure(lConfig.getProperties());
        }
        catch (Exception e) {
            logger.warn(LOGTAG + "No command-specific logger found.");
        }

        // Set the properties for this command.
        try {
            PropertyConfig pConfig = (PropertyConfig) getAppConfig().getObject("GeneralProperties");
            setProperties(pConfig.getProperties());
            logger.info(LOGTAG + "Properties are: " + getProperties().toString());
        }
        catch (EnterpriseConfigurationObjectException e) {
            // An error occurred retrieving a property config from AppConfig. Log it and throw an exception.
            String errMsg = "An error occurred retrieving a property config from AppConfig. The exception is: " + e.getMessage();
            logger.fatal(LOGTAG + errMsg);
            throw new InstantiationException(errMsg);
        }

        // Initialize a provider
        String className = getProperties().getProperty("roleProvisioningProviderClassName");
        if (className == null || className.equals("")) {
            String errMsg = "No roleProvisioningProviderClassName property specified. Can't continue.";
            logger.fatal(LOGTAG + errMsg);
            throw new InstantiationException(errMsg);
        }
        logger.info(LOGTAG + "roleProvisioningProviderClassName is: " + className);

        RoleProvisioningProvider provider;
        try {
            logger.info(LOGTAG + "Getting class for name: " + className);
            provider = (RoleProvisioningProvider) Class.forName(className).newInstance();
            logger.info(LOGTAG + "Initializing RoleProvisioningProvider: " + provider.getClass().getName());
            provider.init(getAppConfig());
            logger.info(LOGTAG + "RoleProvisioningProvider initialized.");
            setProvider(provider);
        }
        catch (ClassNotFoundException e) {
            String errMsg = "Class named " + className + "not found on the classpath.  The exception is: " + e.getMessage();
            logger.fatal(LOGTAG + errMsg);
            throw new InstantiationException(errMsg);
        }
        catch (IllegalAccessException e) {
            String errMsg = "An error occurred getting a class for name: " + className + ". The exception is: " + e.getMessage();
            logger.fatal(LOGTAG + errMsg);
            throw new InstantiationException(errMsg);
        }
        catch (ProviderException e) {
            String errMsg = "An error occurred initializing the RoleProvisioningProvider " + className + ". The exception is: " + e.getMessage();
            logger.fatal(LOGTAG + errMsg);
            throw new InstantiationException(errMsg);
        }

        // Get a SyncService to use to publish sync messages.
        try {
            ProducerPool pool = (ProducerPool) getAppConfig().getObject("SyncPublisher");
            setProducerPool(pool);
        }
        catch (EnterpriseConfigurationObjectException e) {
            String errMsg = "Error retrieving a ProducerPool object from AppConfig. The exception is: " + e.getMessage();
            logger.fatal(LOGTAG + errMsg);
            throw new InstantiationException(errMsg);
        }

        // Verify that we have all required objects in the AppConfig.
        try {
            getAppConfig().getObjectByType(RoleProvisioning.class.getName());
            getAppConfig().getObjectByType(RoleProvisioningRequisition.class.getName());
            getAppConfig().getObjectByType(RoleProvisioningQuerySpecification.class.getName());
        }
        catch (EnterpriseConfigurationObjectException e) {
            String errMsg = "Error retrieving an object from AppConfig: The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new InstantiationException(errMsg);
        }

        logger.info(LOGTAG + "instantiated successfully.");
    }

    /**
     * This method makes a local copy of the response and provide
     * documents to use in the reply to the request. Then it
     * converts the JMS message to an XML document, retrieves the
     * text portion of the message, clears the message body in
     * preparation for the reply, gets the ControlArea from the XML
     * document, and verifies that message object of the message is
     * a RoleProvisioning object and the action is a query,
     * generate, create, update, or delete. Then this method uses the
     * configured RoleProvisioningProvider to perform each
     * operation.
     *
     * @param messageNumber the number of the message processed by the consumer
     * @param aMessage the message for the command to process
     * @throws CommandException with details of the error processing the message
     */
    public final Message execute(int messageNumber, Message aMessage) throws CommandException {
        // Get the execution start time.
        long startTime = System.currentTimeMillis();

        // Make a local copy of the response documents to use in the replies.
        Document localResponseDoc = (Document) getResponseDocument().clone();
        Document localProvideDoc = (Document) getProvideDocument().clone();

        // Convert the JMS Message to an XML Document
        Document inDoc;
        try {
            inDoc = initializeInput(messageNumber, aMessage);
        }
        catch (Exception e) {
            String errMsg = "Exception occurred processing input message.  The exception is: " + e.getMessage();
            throw new CommandException(errMsg);
        }

        // If verbose, write the message body to the log.
        if (getVerbose())
            logger.info("Message sent in is: \n" + getMessageBody(inDoc));

        // Retrieve text portion of message.
        TextMessage msg = (TextMessage) aMessage;
        try {
            // Clear the message body for the reply, so we do not have to do it later.
            msg.clearBody();
        }
        catch (JMSException e) {
            String errMsg = "Error clearing the message body. The exception is: " + e.getMessage();
            throw new CommandException(errMsg);
        }

        // Get the ControlArea from XML document.
        Element eControlArea = getControlArea(inDoc.getRootElement());

        // Get messageAction and messageObject attributes from the ControlArea element.
        String msgAction = eControlArea.getAttribute("messageAction").getValue();
        String msgObject = eControlArea.getAttribute("messageObject").getValue();

        // Verify that the message object we are dealing with is a
        // RoleProvisioning object; if not, reply with an error.
        if (!msgObject.equalsIgnoreCase("RoleProvisioning")) {
            String errType = "application";
            String errCode = "OpenEAI-RoleProvisioning-1001";
            String errDesc = "Unsupported message object: " + msgObject + ". This command expects 'RoleProvisioning'.";
            logger.error(LOGTAG + errDesc);
            logger.error(LOGTAG + "Message sent in is: \n" + getMessageBody(inDoc));
            ArrayList<Error> errors = new ArrayList<>();
            errors.add(buildError(errType, errCode, errDesc));
            String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
            return getMessage(msg, replyContents);
        }

        // Get the senderApplicationId and the authUserId
        Element eSenderAppId = eControlArea.getChild("Sender").getChild("MessageId").getChild("SenderAppId");
        String senderAppId = eSenderAppId.getValue();
        Element eAuthUserId = eControlArea.getChild("Sender").getChild("Authentication").getChild("AuthUserId");
        String authUserId = eAuthUserId.getValue();

        // Temporary workaround for test suite app
    	if (authUserId.equalsIgnoreCase("TestSuiteApplication")) {
    		authUserId = "testsuiteapp@emory.edu/127.0.0.1";
    	}

        // Validate the format of the AuthUserId. If the format is invalid, respond with an error.
        if (!validateAuthUserId(authUserId)) {
            String errType = "application";
            String errCode = "AwsAccountService-RoleProvisioning-1001";
            String errDesc = "Invalid AuthUserId. The value '" + authUserId + "' is not valid. The expected format is user@domain/ip number.";
            logger.fatal(LOGTAG + errDesc);
            logger.fatal(LOGTAG + "Message sent in is: \n" + getMessageBody(inDoc));
            ArrayList<Error> errors = new ArrayList<>();
            errors.add(buildError(errType, errCode, errDesc));
            String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
            return getMessage(msg, replyContents);
        }

        // Get the IP number from the AuthUserId.
//        String ipNumber = getIpNumberFromAuthUserId(authUserId);

        // Get the EPPN from from AuthUserId.
//        String eppn = getEppnFromAuthUserId(authUserId);

        // Get a configured RoleProvisioningRequisition from AppConfig.
        RoleProvisioningRequisition req;
        try {
            req = (RoleProvisioningRequisition) getAppConfig().getObjectByType(RoleProvisioningRequisition.class.getName());
        }
        catch (EnterpriseConfigurationObjectException e) {
            String errMsg = "Error retrieving an object from AppConfig: The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new CommandException(errMsg, e);
        }
        Element eTestId = inDoc.getRootElement().getChild("ControlAreaRequest").getChild("Sender").getChild("TestId");
        TestId testId;
        if (eTestId != null) {
            try {
                testId = (TestId) getAppConfig().getObjectByType(TestId.class.getName());
                testId.buildObjectFromInput(eTestId);
            }
            catch (EnterpriseConfigurationObjectException e) {
                String errMsg = "Error retrieving an object from AppConfig: The exception is: " + e.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new CommandException(errMsg, e);
            }
            catch (EnterpriseLayoutException e) {
                // There was an error building the query object from a query element.
                String errType = "application";
                String errCode = "AwsAccountService-RoleProvisioning-1002";
                String errDesc = "An error occurred building the TestId object from the ControlArea element in the message. The exception is: " + e.getMessage();
                logger.error(LOGTAG + errDesc);
                logger.error("Message sent in is: \n" + getMessageBody(inDoc));
                ArrayList<Error> errors = new ArrayList<>();
                errors.add(buildError(errType, errCode, errDesc));
                String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
                return getMessage(msg, replyContents);
            }

        }
        else {
            testId = null;
        }

        // Handle a Generate-Request.
        if (msgAction.equalsIgnoreCase("Generate")) {
            logger.info(LOGTAG + "Handling a com.amazon.aws.Provisioning.RoleProvisioning.Generate-Request message.");
            Element eGenerateObject = inDoc.getRootElement().getChild("DataArea").getChild("RoleProvisioningRequisition");

            // Verify that the generate object element is not null. If it is null, reply with an error.
            if (eGenerateObject == null) {
                String errType = "application";
                String errCode = "OpenEAI-RoleProvisioning-1002";
                String errDesc = "Invalid element found in the RoleProvisioning.Generate-Request message. This command expects an RoleProvisioningRequisition";
                logger.error(LOGTAG + errDesc);
                logger.error("Message sent in is: \n" + getMessageBody(inDoc));
                ArrayList<Error> errors = new ArrayList<>();
                errors.add(buildError(errType, errCode, errDesc));
                String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
                return getMessage(msg, replyContents);
            }

            // Now build a RoleProvisioningRequisition object from the element in the message.
            try {
                req.buildObjectFromInput(eGenerateObject);
            }
            catch (EnterpriseLayoutException e) {
                // There was an error building the query object from a query element.
                String errType = "application";
                String errCode = "AwsAccountService-RoleProvisioning-1002";
                String errDesc = "An error occurred building the generate object from the DataArea element in the RoleProvisioning.Generate-Request message. The exception is: " + e.getMessage();
                logger.error(LOGTAG + errDesc);
                logger.error("Message sent in is: \n" + getMessageBody(inDoc));
                ArrayList<Error> errors = new ArrayList<>();
                errors.add(buildError(errType, errCode, errDesc));
                String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
                return getMessage(msg, replyContents);
            }

            // Generate the RoleProvisioning object using the provider implementation.
            logger.info(LOGTAG + "Generating an RoleProvisioning object...");

            RoleProvisioning roleProvisioning;
            try {
            	long generateStartTime = System.currentTimeMillis();
                roleProvisioning = getProvider().generate(req);
                if (testId != null)
                    roleProvisioning.setTestId(testId);
                long generateTime = System.currentTimeMillis() - generateStartTime;
                logger.info(LOGTAG + "Generated RoleProvisioning in " + generateTime + " ms." );
            }
            catch (Throwable e) {
                logger.error(LOGTAG, e);
                // There was an error generating the identity
                String errType = "application";
                String errCode = "AwsAccountService-RoleProvisioning-1003";
                String errDesc = "An error occurred generating the RoleProvisioning object. The exception is: " + e.getMessage();
                logger.error(LOGTAG + errDesc);
                logger.error("Message sent in is: \n" + getMessageBody(inDoc));
                ArrayList<Error> errors = new ArrayList<>();
                errors.add(buildError(errType, errCode, errDesc));
                String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
                return getMessage(msg, replyContents);
            }

            // Publish a Create-Sync Message
            logger.info(LOGTAG + "Publishing an RoleProvisioning.Create-Sync message...");
            try {
                MessageProducer producer = getProducerPool().getProducer();
                Authentication auth = new Authentication();
                auth.setAuthUserId(authUserId);
                auth.setAuthUserSignature("none");
                roleProvisioning.setAuthentication(auth);
                roleProvisioning.createSync((SyncService) producer);
                logger.info(LOGTAG + "Published RoleProvisioning.Create-Sync" + " message.");
            }
            catch (EnterpriseObjectSyncException e) {
                String errMsg = "An error occurred publishing the RoleProvisioning.Create-Sync message. The exception is: " + e.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new CommandException(errMsg, e);
            }
            catch (JMSException e) {
                String errMsg = "A JMS error occurred publishing the RoleProvisioning.Create-Sync message. The exception is: " + e.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new CommandException(errMsg, e);
            }

            logger.info(LOGTAG + "Prepare response... " );
            // Prepare the response.
            if (localResponseDoc.getRootElement().getChild("DataArea") != null) {
            	localResponseDoc.getRootElement().getChild("DataArea").removeContent();
            }
            else {
            	localResponseDoc.getRootElement().addContent(new Element("DataArea"));
            }
            Element content;
            try {
                content = (Element) roleProvisioning.buildOutputFromObject();
            }
            catch (EnterpriseLayoutException e) {
                String errMsg = "An error occurred serializing a RoleProvisioning object to an XML element. The exception is: " + e.getMessage();
                logger.error(LOGTAG + errMsg, e);
                throw new CommandException(errMsg, e);
            }
            localResponseDoc.getRootElement().getChild("DataArea").addContent(content);
            String replyContents = buildReplyDocument(eControlArea, localResponseDoc);

            // Log execution time.
            long executionTime = System.currentTimeMillis() - startTime;
            logger.info(LOGTAG + "Generate-Request command execution complete in " + executionTime + " ms.");

            // Return the response with status success.
            return getMessage(msg, replyContents);
        }

        // Handle a Query-Request.
        if (msgAction.equalsIgnoreCase("Query")) {
            logger.info(LOGTAG + "Handling an com.amazon.aws.Provisioning.RoleProvisioning.Query-Request message.");
            Element eQuerySpec = inDoc.getRootElement().getChild("DataArea").getChild("RoleProvisioningQuerySpecification");

            // Get a configured query object from AppConfig.
            RoleProvisioningQuerySpecification querySpec;
            try {
                querySpec = (RoleProvisioningQuerySpecification) getAppConfig().getObjectByType(RoleProvisioningQuerySpecification.class.getName());
            }
            catch (EnterpriseConfigurationObjectException e) {
                String errMsg = "Error retrieving an object from AppConfig: The exception is: " + e.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new CommandException(errMsg);
            }

            // If the query object is null, return and error.
            if (eQuerySpec != null) {
            	try {
                    querySpec.buildObjectFromInput(eQuerySpec);
                }
            	catch (EnterpriseLayoutException e) {
                    // There was an error building the query object from a query
                    // element.
                    String errType = "application";
                    String errCode = "AwsAccountService-RoleProvisioning-1004";
                    String errDesc = "An error occurred building the query object from the DataArea element in the Query-Request message. The exception " + "is: " + e.getMessage();
                    logger.error(LOGTAG + errDesc);
                    logger.error("Message sent in is: \n" + getMessageBody(inDoc));
                    ArrayList<Error> errors = new ArrayList<>();
                    errors.add(buildError(errType, errCode, errDesc));
                    String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
                    return getMessage(msg, replyContents);
                }
            }
            else {
                // The query spec is null.
                String errType = "application";
                String errCode = "AwsAccountService-RoleProvisioning-1005";
                String errDesc = "An error occurred building the query object from the DataArea element in the Query-Request message. The query spec is null.";
                logger.error(LOGTAG + errDesc);
                logger.error("Message sent in is: \n" + getMessageBody(inDoc));
                ArrayList<Error> errors = new ArrayList<>();
                errors.add(buildError(errType, errCode, errDesc));
                String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
                return getMessage(msg, replyContents);
            }

            // Query for the RoleProvisioning from the provider.
            logger.info(LOGTAG + "Querying for the RoleProvisioning...");

            List<RoleProvisioning> results;
            try {
            	long queryStartTime = System.currentTimeMillis();
                results = getProvider().query(querySpec);
                long queryTime = System.currentTimeMillis() - queryStartTime;
                logger.info(LOGTAG + "Queried for RoleProvisioning in " + queryTime + "ms.");
            }
            catch (ProviderException e) {
                // There was an error generating the identity
                String errType = "application";
                String errCode = "AwsAccountService-RoleProvisioning-1006";
                String errDesc = "An error occurred querying for the RoleProvisioning. The exception is: " + e.getMessage();
                logger.error(LOGTAG + errDesc);
                logger.error("Message sent in is: \n" + getMessageBody(inDoc));
                ArrayList<Error> errors = new ArrayList<>();
                errors.add(buildError(errType, errCode, errDesc));
                String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
                return getMessage(msg, replyContents);
            }

            if (results != null) {
            	logger.info(LOGTAG + "Found " + results.size() + " matching result(s).");
            }
            else {
            	logger.info(LOGTAG + "Results are null; no matching RoleProvisioning found.");
            }

            // Prepare the response.
            localProvideDoc.getRootElement().getChild("DataArea").removeContent();
            // If there are results, place them in the response.
            if (results != null && results.size() > 0) {
                ArrayList<Element> adList = new ArrayList<>();
                for (int i = 0; i < results.size(); i++) {
                    try {
                        RoleProvisioning ad = results.get(i);
                        if (testId != null)
                            ad.setTestId(testId);
                        adList.add((Element) ad.buildOutputFromObject());
                    }
                    catch (EnterpriseLayoutException e) {
                        String errMsg = "An error occurred serializing RoleProvisioning object to an XML element. The exception is: " + e.getMessage();
                        logger.error(LOGTAG + errMsg);
                        throw new CommandException(errMsg, e);
                    }
                }
                localProvideDoc.getRootElement().getChild("DataArea").addContent(adList);
            }
            String replyContents = buildReplyDocument(eControlArea, localProvideDoc);

            // Log execution time.
            long executionTime = System.currentTimeMillis() - startTime;
            logger.info(LOGTAG + "Query-Request command execution complete in " + executionTime + " ms.");

            // Return the response with status success.
            return getMessage(msg, replyContents);
        }

        // Handle a Create-Request.
        if (msgAction.equalsIgnoreCase("Create")) {
            logger.info(LOGTAG + "Handling a com.amazon.aws.Provisioning.RoleProvisioning.Create-Request message.");
            Element eRp = inDoc.getRootElement().getChild("DataArea").getChild("NewData").getChild("RoleProvisioning");

            // Verify that the RoleProvisioning element is not null. If it is null, reply with an error.
            if (eRp == null) {
                String errType = "application";
                String errCode = "OpenEAI-RoleProvisioning-1003";
                String errDesc = "Invalid element found in the Create-Request message. This command expects an RoleProvisioning";
                logger.error(LOGTAG + errDesc);
                logger.error("Message sent in is: \n" + getMessageBody(inDoc));
                ArrayList<Error> errors = new ArrayList<>();
                errors.add(buildError(errType, errCode, errDesc));
                String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
                return getMessage(msg, replyContents);
            }

            // Get a configured RoleProvisioning from AppConfig.
            RoleProvisioning ad;
            try {
                ad = (RoleProvisioning) getAppConfig().getObjectByType(RoleProvisioning.class.getName());
            }
            catch (EnterpriseConfigurationObjectException e) {
                String errMsg = "Error retrieving an object from AppConfig: The exception is: " + e.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new CommandException(errMsg);
            }

            // Now build an RoleProvisioning object from the element in the message.
            try {
                ad.buildObjectFromInput(eRp);
                if (testId != null)
                	ad.setTestId(testId);
                logger.info(LOGTAG + "TestId is: " + ad.getTestId().toString());
            }
            catch (EnterpriseLayoutException e) {
                // There was an error building the delete object from the delete element.
                String errType = "application";
                String errCode = "AwsAccountService-RoleProvisioning-1007";
                String errDesc = "An error occurred building the delete object from the DataArea element in the Create-Request message. The exception " + "is: " + e.getMessage();
                logger.error(LOGTAG + errDesc);
                logger.error("Message sent in is: \n" + getMessageBody(inDoc));
                ArrayList<Error> errors = new ArrayList<>();
                errors.add(buildError(errType, errCode, errDesc));
                String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
                return getMessage(msg, replyContents);
            }

            // Create the RoleProvisioning object using the provider.
            logger.info(LOGTAG + "Creating an RoleProvisioning...");

            try {
            	long createStartTime = System.currentTimeMillis();
                getProvider().create(ad);
                long createTime = System.currentTimeMillis() - createStartTime;
                logger.info(LOGTAG + "Created RoleProvisioning in " + createTime + " ms.");
            }
            catch (ProviderException e) {
                // There was an error creating the RoleProvisioning
                String errType = "application";
                String errCode = "AwsAccountService-RoleProvisioning-1008";
                String errDesc = "An error occurred deleting the RoleProvisioning. The exception is: " + e.getMessage();
                logger.error(LOGTAG + errDesc);
                logger.error("Message sent in is: \n" + getMessageBody(inDoc));
                ArrayList<Error> errors = new ArrayList<>();
                errors.add(buildError(errType, errCode, errDesc));
                String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
                return getMessage(msg, replyContents);
            }

            // Publish a Create-Sync Message
            try {
                MessageProducer producer = getProducerPool().getProducer();
                ad.createSync((SyncService) producer);
                logger.info(LOGTAG + "Published RoleProvisioning.Create-Sync message.");
            }
            catch (EnterpriseObjectSyncException e) {
                String errMsg = "An error occurred publishing the RoleProvisioning.Create-Sync message after creating the RoleProvisioning object. The exception is: " + e.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new CommandException(errMsg, e);
            }
            catch (JMSException e) {
            	String errMsg = "A JMS error occurred publishing the RoleProvisioning.Create-Sync message after creating the RoleProvisioning object. The exception is: " + e.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new CommandException(errMsg, e);
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
            logger.info(LOGTAG + "Handling a com.amazon.aws.Provisioning.RoleProvisioning.Update-Request message.");

            // Verify that the baseline is not null.
            Element eBaselineData = inDoc.getRootElement().getChild("DataArea").getChild("BaselineData").getChild("RoleProvisioning");
            Element eNewData = inDoc.getRootElement().getChild("DataArea").getChild("NewData").getChild("RoleProvisioning");
            if (eNewData == null || eBaselineData == null) {
                String errMsg = "Either the baseline or new data state of the RoleProvisioning is null. Can't continue.";
                throw new CommandException(errMsg);
            }

            // Get configured objects from AppConfig.
            RoleProvisioning baselineAd;
            RoleProvisioning newAd;
            try {
                baselineAd = (RoleProvisioning) getAppConfig().getObjectByType(RoleProvisioning.class.getName());
                newAd = (RoleProvisioning) getAppConfig().getObjectByType(RoleProvisioning.class.getName());
            }
            catch (EnterpriseConfigurationObjectException e) {
                String errMsg = "An error occurred retrieving an object from AppConfig. The exception is: " + e.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new CommandException(errMsg, e);
            }

            // Build the baseline and newdata states of the RoleProvisioning.
            try {
                baselineAd.buildObjectFromInput(eBaselineData);
                newAd.buildObjectFromInput(eNewData);
                if (testId != null) {
                	newAd.setTestId(testId);
                    logger.info(LOGTAG + "TestId is: " + newAd.getTestId().toString());
                }
            }
            catch (EnterpriseLayoutException e) {
                String errMsg = "An error occurred building the baseline and newdata states of the RoleProvisioning object passed in. The exception is: " + e.getMessage();
                throw new CommandException(errMsg, e);
            }

            // Perform the baseline check.

            // Get a configured RoleProvisioningQuerySpecification from AppConfig.
            RoleProvisioningQuerySpecification querySpec;
            try {
                querySpec = (RoleProvisioningQuerySpecification) getAppConfig().getObjectByType(RoleProvisioningQuerySpecification.class.getName());
            }
            catch (EnterpriseConfigurationObjectException e) {
                String errMsg = "Error retrieving an object from AppConfig: The exception is: " + e.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new CommandException(errMsg);
            }

            try {
            	querySpec.setRoleProvisioningId(baselineAd.getRoleProvisioningId());
            }
            catch (EnterpriseFieldException e) {
            	String errMsg = "An error occurred setting the field values of the RoleProvisioning query specification. The exception is: " + e.getMessage();
            	logger.error(LOGTAG + errMsg);
            	throw new CommandException(errMsg, e);
            }

            // Query for the RoleProvisioning.
            RoleProvisioning ad = null;
            try {
            	logger.info(LOGTAG + "Querying for baseline RoleProvisioning...");
                List<RoleProvisioning> adList = getProvider().query(querySpec);
                logger.info(LOGTAG + "Found " + adList.size() + " result(s).");
                if (adList.size() > 0)
                    ad = adList.get(0);
            }
            catch (ProviderException e) {
                // There was an error querying the RoleProvisioning service
                String errType = "application";
                String errCode = "AwsAccountService-RoleProvisioning-1009";
                String errDesc = "An error occurred querying the RoleProvisioning provider to verify the baseline state of the RoleProvisioning. The exception is: " + e.getMessage();
                logger.error(LOGTAG + errDesc);
                logger.error("Message sent in is: \n" + getMessageBody(inDoc));
                ArrayList<Error> errors = new ArrayList<>();
                errors.add(buildError(errType, errCode, errDesc));
                String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
                return getMessage(msg, replyContents);
            }

            if (ad != null) {
                // Compare the retrieved baseline with the baseline in the update request message.
                try {
                    if (baselineAd.equals(ad)) {
                        logger.info(LOGTAG + "Baseline matches the current state of the RoleProvisioning in the AWS Account Service.");
                    } else {
                        logger.info(LOGTAG + "Baseline does not match the current state of the RoleProvisioning in the AWS Account Service.");
                        String errType = "application";
                        String errCode = "OpenEAI-RoleProvisioning-1004";
                        String errDesc = "Baseline is stale.";
                        logger.error(LOGTAG + errDesc);
                        logger.error("Message sent in is: \n" + getMessageBody(inDoc));
                        ArrayList<Error> errors = new ArrayList<>();
                        errors.add(buildError(errType, errCode, errDesc));
                        String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
                        return getMessage(msg, replyContents);
                    }
                }
                catch (XmlEnterpriseObjectException e) {
                    // Respond with an error, because no RoleProvisioning matching the baseline could be found.
                    String errType = "application";
                    String errCode = "OpenEAI-RoleProvisioning-1005";
                    String errDesc = "Baseline stale error in comparison.";
                    logger.error(LOGTAG + errDesc);
                    logger.error("Message sent in is: \n" + getMessageBody(inDoc));
                    ArrayList<Error> errors = new ArrayList<>();
                    errors.add(buildError(errType, errCode, errDesc));
                    String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
                    return getMessage(msg, replyContents);
                }

            } else {
                // Respond with an error, because no RoleProvisioning matching the baseline could be found.
                String errType = "application";
                String errCode = "OpenEAI-RoleProvisioning-1006";
                String errDesc = "Baseline is stale. No baseline found.";
                logger.error(LOGTAG + errDesc);
                logger.error("Message sent in is: \n" + getMessageBody(inDoc));
                ArrayList<Error> errors = new ArrayList<>();
                errors.add(buildError(errType, errCode, errDesc));
                String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
                return getMessage(msg, replyContents);
            }

            // Verify that the baseline and the new state are not equal.
            try {
                if (baselineAd.equals(newAd)) {
                    String errType = "application";
                    String errCode = "AwsAccountService-RoleProvisioning-1010";
                    String errDesc = "Baseline state and new state of the object are equal. No update operation may be performed.";
                    logger.error(LOGTAG + errDesc);
                    logger.error("Message sent in is: \n" + getMessageBody(inDoc));
                    ArrayList<Error> errors = new ArrayList<>();
                    errors.add(buildError(errType, errCode, errDesc));
                    String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
                    return getMessage(msg, replyContents);
                }
            }
            catch (XmlEnterpriseObjectException e) {
                String errMsg = "An error occurred comparing the baseline and new data. The exception is: " + e.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new CommandException(errMsg, e);
            }

            // Update the RoleProvisioning object using the provider.
            try {
                long updateStartTime = System.currentTimeMillis();
                logger.info(LOGTAG + "Updating the RoleProvisioning object in the provider...");
                getProvider().update(newAd);
                long updateTime = System.currentTimeMillis() - updateStartTime;
                logger.info(LOGTAG + "RoleProvisioning update processed by provider in " + updateTime + " ms.");
            }
            catch (ProviderException e) {
                // There was an error updating the RoleProvisioning
                String errType = "application";
                String errCode = "AwsAccountService-RoleProvisioning-1011";
                String errDesc = "An error occurred updating the object. The " + "exception is: " + e.getMessage();
                logger.error(LOGTAG + errDesc);
                logger.error("Message sent in is: \n" + getMessageBody(inDoc));
                ArrayList<Error> errors = new ArrayList<>();
                errors.add(buildError(errType, errCode, errDesc));
                String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
                return getMessage(msg, replyContents);
            }
            logger.info(LOGTAG + "Updated RoleProvisioning: " + newAd.toString());

            // Set the baseline on the new state of the RoleProvisioning.
            newAd.setBaseline(baselineAd);

            // Publish an Update-Sync Message
            try {
                MessageProducer producer = getProducerPool().getProducer();
                long publishStartTime = System.currentTimeMillis();
                newAd.updateSync((SyncService) producer);
                long publishTime = System.currentTimeMillis() - publishStartTime;
                logger.info(LOGTAG + "Published RoleProvisioning.Update-Sync message in " + publishTime + " ms.");
            }
            catch (EnterpriseObjectSyncException e) {
                String errMsg = "An error occurred publishing the RoleProvisioning.Update-Sync message after updating the the RoleProvisioning object. The exception is: " + e.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new CommandException(errMsg, e);
            }
            catch (JMSException e) {
                String errMsg = "An error occurred publishing the RoleProvisioning.Update-Sync message after generating an identity. The exception is: " + e.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new CommandException(errMsg, e);
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
            logger.info(LOGTAG + "Handling a com.amazon.aws.Provisioning.RoleProvisioning.Delete-Request message.");
            Element eRoleProvisioning = inDoc.getRootElement().getChild("DataArea").getChild("DeleteData").getChild("RoleProvisioning");

            // Verify that the RoleProvisioning element is not null. If it is
            // null, reply with an error.
            if (eRoleProvisioning == null) {
                String errType = "application";
                String errCode = "OpenEAI-RoleProvisioning-1007";
                String errDesc = "Invalid element found in the Delete-Request message. This command expects an RoleProvisioning";
                logger.error(LOGTAG + errDesc);
                logger.error("Message sent in is: \n" + getMessageBody(inDoc));
                ArrayList<Error> errors = new ArrayList<>();
                errors.add(buildError(errType, errCode, errDesc));
                String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
                return getMessage(msg, replyContents);
            }

            // Get a configured RoleProvisioning from AppConfig.
            RoleProvisioning ad;
            try {
                ad = (RoleProvisioning) getAppConfig().getObjectByType(RoleProvisioning.class.getName());
            }
            catch (EnterpriseConfigurationObjectException e) {
                String errMsg = "Error retrieving an object from AppConfig: The exception" + "is: " + e.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new CommandException(errMsg);
            }

            // Now build an RoleProvisioning object from the element in the message.
            try {
                ad.buildObjectFromInput(eRoleProvisioning);
                if (testId != null)
                    ad.setTestId(testId);
            }
            catch (EnterpriseLayoutException e) {
                // There was an error building the delete object from the delete element.
                String errType = "application";
                String errCode = "AwsAccountService-RoleProvisioning-1012";
                String errDesc = "An error occurred building the delete object from the DataArea element in the Delete-Request message. The exception is: " + e.getMessage();
                logger.error(LOGTAG + errDesc);
                logger.error("Message sent in is: \n" + getMessageBody(inDoc));
                ArrayList<Error> errors = new ArrayList<>();
                errors.add(buildError(errType, errCode, errDesc));
                String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
                return getMessage(msg, replyContents);
            }

            // Delete the RoleProvisioning object using the provider.
            logger.info(LOGTAG + "Deleting an RoleProvisioning object...");

            try {
            	long deleteStartTime = System.currentTimeMillis();
                getProvider().delete(ad);
                long deleteTime = System.currentTimeMillis() - deleteStartTime;
                logger.info(LOGTAG + "Deleted RoleProvisioning in " + deleteTime + " ms.");
            }
            catch (ProviderException e) {
                // There was an error deleting the RoleProvisioning
                String errType = "application";
                String errCode = "AwsAccountService-RoleProvisioning-1013";
                String errDesc = "An error occurred deleting the RoleProvisioning object. The exception is: " + e.getMessage();
                logger.error(LOGTAG + errDesc);
                logger.error("Message sent in is: \n" + getMessageBody(inDoc));
                ArrayList<Error> errors = new ArrayList<>();
                errors.add(buildError(errType, errCode, errDesc));
                String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
                return getMessage(msg, replyContents);
            }

            // Publish a Delete-Sync Message
            try {
                MessageProducer producer = getProducerPool().getProducer();
                ad.deleteSync("delete", (SyncService) producer);
                logger.info(LOGTAG + "Published RoleProvisioning.Delete-Sync" + " message.");
            }
            catch (EnterpriseObjectSyncException e) {
                String errMsg = "An error occurred publishing the RoleProvisioning.Delete-Sync message after deleting the RoleProvisioning object. The exception is: " + e.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new CommandException(errMsg, e);
            }
            catch (JMSException e) {
            	String errMsg = "A JMS error occurred publishing the RoleProvisioning.Delete-Sync message after deleting the RoleProvisioning object. The exception is: " + e.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new CommandException(errMsg, e);
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
            // The messageAction is invalid; it is not a query, generate, create. update, or delete
            String errType = "application";
            String errCode = "OpenEAI-RoleProvisioning-1008";
            String errDesc = "Unsupported message action: " + msgAction + ". This command only supports query, generate, create, update, and delete.";
            logger.fatal(LOGTAG + errDesc);
            logger.fatal("Message sent in is: \n" + getMessageBody(inDoc));
            ArrayList<Error> errors = new ArrayList<>();
            errors.add(buildError(errType, errCode, errDesc));
            String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
            return getMessage(msg, replyContents);
        }
    }

    /**
     * Sets the RoleProvisioning provider for this command.
     * @param provider the RoleProvisioning provider
     */
    protected void setProvider(RoleProvisioningProvider provider) {
        m_provider = provider;
    }

    /**
     * Gets the RoleProvisioning provider for this command.
     * @return the RoleProvisioning provider
     */
    protected RoleProvisioningProvider getProvider() {
        return m_provider;
    }

    /**
     * Sets the producer pool for this command.
     * @param producerPool the producer pool for this command.
     */
    protected void setProducerPool(ProducerPool producerPool) {
        m_producerPool = producerPool;
    }

    /**
     * Gets the producer pool for this command.
     * @return the producer pool for this command.
     */
    protected ProducerPool getProducerPool() {
        return m_producerPool;
    }

    /**
     * Parses the EPPN from the AuthUserId and returns it.
     * @return a String containing the EPPN.
     */
    private String getEppnFromAuthUserId(String authUserId) {
        StringTokenizer st = new StringTokenizer(authUserId, "/");
        return st.nextToken();
    }

    /**
     * Parses the IP number from the AuthUserId and returns it.
     * @return a String containing the IP number.
     */
    private String getIpNumberFromAuthUserId(String authUserId) {
        StringTokenizer st = new StringTokenizer(authUserId, "/");
        st.nextToken();
        return st.nextToken();
    }

    /**
     * Validates the format of the AuthUserId to be eppn/ipNumber. More
     * specifically, this is user@domain/ipnumber.
     *
     * @param authUserId user
     * @return a flag indicating whether or not the authUserId id is valid.
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
        if (!GenericValidator.isEmail(eppn)) {
            logger.error(LOGTAG + "EPPN is not a valid e-mail: " + eppn);
            return false;
        }

        // Validate the IP number.
        String ip = st.nextToken();
        InetAddressValidator iav = new InetAddressValidator();
        if (!iav.isValid(ip)) {
            logger.info(LOGTAG + "IP number is not valid: " + ip);
            return false;
        }

        // If all validation checks have passed, return true.
        return true;
    }
}
