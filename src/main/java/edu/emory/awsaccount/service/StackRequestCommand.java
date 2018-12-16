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
import com.amazon.aws.moa.objects.resources.v1_0.StackQuerySpecification;
import com.amazon.aws.moa.objects.resources.v1_0.StackRequisition;
import com.amazon.aws.moa.jmsobjects.cloudformation.v1_0.Stack;


// VPC Provider Implementation
import edu.emory.awsaccount.service.provider.StackProvider;
import edu.emory.awsaccount.service.provider.ProviderException;

//Apache Commons Validators
import org.apache.commons.validator.GenericValidator;
import org.apache.commons.validator.routines.InetAddressValidator;

/**
 * This command handles requests for Stack objects.
 * Specifically, it handles a Query-Request, a Generate-Request, an Update-Request,
 * and a Delete-Request for this object with the following high-level 
 * logic:
 * <P>
 * <OL>
 * <LI>com.amazon.aws.CloudFormation.Stack.Query-Request (<A HREF=
 * "https://svn.service.emory.edu:8443/cgi-bin/viewvc.cgi/emoryoit/message/releases/com/amazon/aws/CloudFormation/Stack/1.0/dtd/Query-Request.dtd?view=markup"
 * >Definition</A> | <A HREF=
 * "https://svn.service.emory.edu:8443/cgi-bin/viewvc.cgi/emoryoit/message/releases/com/amazon/aws/CloudFormation/Stack/1.0/xml/Query-Request.xml?view=markup"
 * >Sample Message</A>)
 * <UL>
 * <LI>Convert the JMS message to an XML document</LI>
 * <LI>Build a message object from the XML document for the
 * StackQuerySpecification</LI>
 * <LI>Invoke the query method of the configured stack provider</LI>
 * <LI>Build the message response from the results of the query
 * operation. This should contain a list of Stack objects
 * from which one can build a list of Stack objects.</LI>
 * <LI>Return the response</LI>
 * </UL>
 * </LI>
 * <LI>com.amazon.aws.CloudFormation.Stack.Generate-Request (<A
 * HREF=
 * "https://svn.service.emory.edu:8443/cgi-bin/viewvc.cgi/emoryoit/message/releases/com/amazon/aws/CloudFormation/Stack/1.0/dtd/Generate-Request.dtd?view=markup"
 * >Definition</A> | <A HREF=
 * "https://svn.service.emory.edu:8443/cgi-bin/viewvc.cgi/emoryoit/message/releases/com/amazon/aws/CloudFormation/Stack/1.0/xml/Generate-Request.xml?view=markup"
 * >Sample Message</A>)
 * <UL>
 * <LI>Convert the JMS message to and XML document</LI>
 * <LI>Build a message object from the XML document for the
 * StackRequisition object</LI>
 * <LI>Invoke the generate method of the
 * configured stack provider. </LI>
 * <LI>Build the Stack object from the results of the
 * generate operation. This should always return a
 * Stack in the success case.</LI>
 * <LI>If successful, publish a Stack.Create-Sync
 * message</LI>
 * <LI>Build the response to the request message</LI>
 * <LI>Return the response to the request message</LI>
 * </UL>
 * </LI>
 * <LI>com.amazon.aws.CloudFormation.Stack.Update-Request (<A HREF=
 * "https://svn.service.emory.edu:8443/cgi-bin/viewvc.cgi/emoryoit/message/releases/com/amazon/aws/CloudFormation/Stack/1.0/dtd/Update-Request.dtd?view=markup"
 * >Definition</A> | <A HREF=
 * "https://svn.service.emory.edu:8443/cgi-bin/viewvc.cgi/emoryoit/message/releases/com/amazon/aws/CloudFormation/Stack/1.0/xml/Update-Request.xml?view=markup"
 * >Sample Message</A>)
 * <UL>
 * <LI>Convert the JMS message to and XML document</LI>
 * <LI>Build a message objects for the XML document for the
 * baseline and new Stack objects</LI>
 * <LI>Perform baseline comparison</LI>
 * <LI>Invoke the update method of the
 * configured Stack provider</LI>
 * <LI>If successful, publish a Stack.Update-Sync
 * message</LI>
 * <LI>Build the response to the request message</LI>
 * <LI>Return the response to the request message</LI>
 * </UL>
 * </LI>
 * <LI>com.amazon.aws.CloudFormation.Stack.Delete-Request (<A HREF=
 * "https://svn.service.emory.edu:8443/cgi-bin/viewvc.cgi/emoryoit/message/releases/com/amazon/aws/CloudFormation/Stack/1.0/dtd/Delete-Request.dtd?view=markup"
 * >Definition</A> | <A HREF=
 * "https://svn.service.emory.edu:8443/cgi-bin/viewvc.cgi/emoryoit/message/releases/com/amazon/aws/CloudFormation/Stack/1.0/xml/Delete-Request.xml?view=markup"
 * >Sample Message</A>)
 * <UL>
 * <LI>Convert the JMS message to and XML document</LI>
 * <LI>Build a message object from the XML document for the
 * Stack object</LI>
 * <LI>Invoke the delete method of the
 * configured stack provider</LI>
 * <LI>If successful, publish a Stack.Delete-Sync
 * message</LI>
 * <LI>Build the response to the request message</LI>
 * <LI>Return the response to the request message</LI>
 * </UL>
 * </LI>
 *
 * </OL>
 * 
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 26 December 2016
 * 
 */

public class StackRequestCommand extends AwsAccountRequestCommand implements RequestCommand {
    private static String LOGTAG = "[StackRequestCommand] ";
    private static Logger logger = Logger.getLogger(StackRequestCommand.class);
    private StackProvider m_provider = null;
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
    public StackRequestCommand(CommandConfig cConfig) throws InstantiationException {
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

        // Initialize a StackProvider
        String className = getProperties().getProperty("stackProviderClassName");
        if (className == null || className.equals("")) {
            String errMsg = "No stackProviderClassName property "
                    + "specified. Can't continue.";
            logger.fatal(LOGTAG + errMsg);
            throw new InstantiationException(errMsg);
        }
        logger.info(LOGTAG + "stackProviderClassName is: " + className);

        StackProvider provider = null;
        try {
            logger.info(LOGTAG + "Getting class for name: " + className);
            Class providerClass = Class.forName(className);
            if (providerClass == null)
                logger.info(LOGTAG + "providerClass is null.");
            else
                logger.info(LOGTAG + "providerClass is not null.");
            provider = (StackProvider) Class.forName(className).newInstance();
            logger.info(LOGTAG + "Initializing StackProvider: "
                    + provider.getClass().getName());
            provider.init(getAppConfig());
            logger.info(LOGTAG + "StackProvider initialized.");
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
            String errMsg = "An error occurred initializing the " + "StackProvider " + className
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
        // Get a configured Stack from AppConfig.
        Stack stack = new Stack();
        try {
            stack = (Stack) getAppConfig()
            	.getObjectByType(stack.getClass().getName());
        } catch (EnterpriseConfigurationObjectException eoce) {
            String errMsg = "Error retrieving an object from AppConfig: The exception" + "is: " + eoce.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new InstantiationException(errMsg);
        }

        // Get a StackQuerySpecification from AppConfig.
        StackQuerySpecification querySpec = new StackQuerySpecification();
        try {
            querySpec = (StackQuerySpecification) getAppConfig()
            	.getObjectByType(querySpec.getClass().getName());
        } catch (EnterpriseConfigurationObjectException eoce) {
            String errMsg = "Error retrieving an object from AppConfig: " +
            	"The exception" + "is: " + eoce.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new InstantiationException(errMsg);
        }
        
        // Get a StackRequisition from AppConfig.
        StackRequisition req = new StackRequisition();
        try {
            req = (StackRequisition) getAppConfig()
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
     *             configured StackProvider to perform each
     *             operation.
     */
    public final Message execute(int messageNumber, Message aMessage) throws CommandException {
    	
    	// Get the execution start time.
        long startTime = System.currentTimeMillis();
        
        String LOGTAG = "[StackRequestCommand.execute] ";
        logger.info(LOGTAG + "Executing...");

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
            logger.error(LOGTAG + errMs);
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
            logger.error(LOGTAG + "Executing...");
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
        if (msgObject.equalsIgnoreCase("Stack") == false) {
            String errType = "application";
            String errCode = "OpenEAI-1001";
            String errDesc = "Unsupported message object: " + msgObject
                    + ". This command expects 'Stack'.";
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
            logger.error(LOGTAG + errDesc);
            logger.error(LOGTAG + "Message sent in is: \n" + getMessageBody(inDoc));
            ArrayList errors = new ArrayList();
            errors.add(buildError(errType, errCode, errDesc));
            String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
            return getMessage(msg, replyContents);
        }

        // Get the IP number from the AuthUserId.
        String ipNumber = getIpNumberFromAuthUserId(authUserId);

        // Get the EPPN from from AuthUserId.
        String eppn = getEppnFromAuthUserId(authUserId);

        // Get a configured StackRequisition from AppConfig.
        StackRequisition req = new StackRequisition();
        TestId testId = new TestId();
        try {
            req = (StackRequisition) getAppConfig().getObjectByType(req.getClass().getName());
            testId = (TestId) getAppConfig().getObjectByType(testId.getClass().getName());
        } catch (EnterpriseConfigurationObjectException eoce) {
            String errMsg = "Error retrieving an object from AppConfig: The exception" + "is: " + eoce.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new CommandException(errMsg, eoce);
        }
        Element eTestId = inDoc.getRootElement().getChild("ControlAreaRequest").getChild("Sender").getChild("TestId");

        // Handle a Generate-Request.
        if (msgAction.equalsIgnoreCase("Generate")) {
            logger.info(LOGTAG + "Handling a com.amazon.aws.CloudFormation.Stack.Generate-Request"
                    + " message.");
            Element eGenerateObject = inDoc.getRootElement().getChild("DataArea").getChild("StackRequisition");

            // Verify that the generate object element is not null. If it is
            // null, reply
            // with an error.
            if (eGenerateObject == null) {
                String errType = "application";
                String errCode = "OpenEAI-1015";
                String errDesc = "Invalid element found in the Generate-Request "
                        + "message. This command expects a StackRequisition";
                logger.error(LOGTAG + errDesc);
                logger.error("Message sent in is: \n" + getMessageBody(inDoc));
                ArrayList errors = new ArrayList();
                errors.add(buildError(errType, errCode, errDesc));
                String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
                return getMessage(msg, replyContents);
            }

            // Now build a StackRequisition object from the element in the message.
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

            // Generate the Stack object using the provider implementation.
            logger.info(LOGTAG + "Generating a Stack...");

            Stack stack = null;
            try {
            	long generateStartTime = System.currentTimeMillis();
                stack = getProvider().generate(req);
                long generateTime = System.currentTimeMillis() - generateStartTime;
                logger.info(LOGTAG + "Generate Stack in " + generateTime + " ms." );
                if (eTestId != null)
                    stack.setTestId(testId);
            } catch (Throwable pe) {
                logger.error(LOGTAG, pe);
                // There was an error generating the identity
                String errType = "application";
                String errCode = "AwsAccountService-1003";
                String errDesc = "An error occurred generating the Stack. The " + "exception is: " + pe.getMessage();
                logger.error(LOGTAG + errDesc);
                logger.error("Message sent in is: \n" + getMessageBody(inDoc));
                ArrayList errors = new ArrayList();
                errors.add(buildError(errType, errCode, errDesc));
                String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
                return getMessage(msg, replyContents);
            }
            logger.info(LOGTAG + "Generated Stack: " + stack.toString());

            logger.info(LOGTAG + "Publishing sync... " );
            // Publish a Create-Sync Message
            try {
                MessageProducer producer = getProducerPool().getProducer();
                if (getVerbose())
                    logger.info(LOGTAG + "Publishing Stack.Create-Sync message...");
                Authentication auth = new Authentication();
                auth.setAuthUserId(authUserId);
                auth.setAuthUserSignature("none");
                stack.setAuthentication(auth);
                stack.createSync((SyncService) producer);
                logger.info(LOGTAG + "Published Stack.Create-Sync" + " message.");
            } catch (EnterpriseObjectSyncException eose) {
                String errMsg = "An error occurred publishing the Stack.Create-Sync"
                        + " message after generating a Stack. The " + "exception is: "
                        + eose.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new CommandException(errMsg, eose);
            } catch (JMSException jmse) {
                String errMsg = "An error occurred publishing the Stack.Create-Sync"
                        + " message after generating a Stack. The " + "exception is: "
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
            Element eStack = null;
            try {
                eStack = (Element) stack.buildOutputFromObject();
            } catch (EnterpriseLayoutException ele) {
                String errMsg = "An error occurred serializing a Stack object to an XML element. The exception is: "
                        + ele.getMessage();
                logger.error(LOGTAG + errMsg, ele);
                throw new CommandException(errMsg, ele);
            }
            localResponseDoc.getRootElement().getChild("DataArea").addContent(eStack);
            String replyContents = buildReplyDocument(eControlArea, localResponseDoc);

            // Log execution time.
            long executionTime = System.currentTimeMillis() - startTime;
            logger.info(LOGTAG + "Generate-Request command execution complete in " + executionTime + " ms.");

            // Return the response with status success.
            return getMessage(msg, replyContents);
        }
        
        // Handle a Query-Request.
        if (msgAction.equalsIgnoreCase("Query")) {
            logger.info(LOGTAG + "Handling an com.amazon.aws.CloudFormation.Stack."
                    + "Query-Request message.");
            Element eQuerySpec = inDoc.getRootElement().getChild("DataArea")
                    .getChild("StackQuerySpecification");

            // Get a configured query object from AppConfig.            
            StackQuerySpecification querySpec = new StackQuerySpecification();
            try {
                querySpec = (StackQuerySpecification) getAppConfig().getObjectByType(
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

            // Query for the Stack from the provider.
            logger.info(LOGTAG + "Querying for the Stack...");

            List results = null;
            try {
            	long queryStartTime = System.currentTimeMillis();
                results = getProvider().query(querySpec);
                long queryTime = System.currentTimeMillis() - queryStartTime;
                logger.info(LOGTAG + "Queried for Stack in " + queryTime + "ms.");
            } catch (ProviderException pe) {
                // There was an error generating the identity
                String errType = "application";
                String errCode = "AwsAccountService-100X";
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
            	logger.info(LOGTAG + "Results are null; no matching VPC found.");
            }

            // Prepare the response.
            localProvideDoc.getRootElement().getChild("DataArea").removeContent();
            // If there are results, place them in the response.
            if (results != null && results.size() > 0) {
            	
                ArrayList stackList = new ArrayList();
                for (int i = 0; i < results.size(); i++) {
                    Element eStack = null;
                    try {
                        Stack stack = (Stack) results.get(i);
                        eStack = (Element) stack.buildOutputFromObject();
                        stackList.add(eStack);
                        if (eTestId != null)
                            stack.setTestId(testId);
                    } catch (EnterpriseLayoutException ele) {
                        String errMsg = "An error occurred serializing "
                                + "Stack object to an XML element. " 
                        		+ "The exception is: " + ele.getMessage();
                        logger.error(LOGTAG + errMsg);
                        throw new CommandException(errMsg, ele);
                    }
                }
                localProvideDoc.getRootElement().getChild("DataArea").addContent(stackList);
            }
            String replyContents = buildReplyDocument(eControlArea, localProvideDoc);

            // Return the response with status success.
            return getMessage(msg, replyContents);
        }
/**                    
        // Handle an Update-Request.
        if (msgAction.equalsIgnoreCase("Update")) {
            logger.info(LOGTAG + "Handling a com.amazon.aws.CloudFormation." +
            		"Stack.Update-Request message.");

            // Verify that the baseline is not null.
            Element eBaselineData = inDoc.getRootElement().getChild("DataArea").getChild("BaselineData")
                    .getChild("Stack");
            Element eNewData = inDoc.getRootElement().getChild("DataArea").getChild("NewData")
                    .getChild("Stack");
            if (eNewData == null || eBaselineData == null) {
                String errMsg = "Either the baseline or new data state of the "
                        + "Stack is null. Can't continue.";
                throw new CommandException(errMsg);
            }

            // Get configured objects from AppConfig.
            Stack baselineStack = new Stack();
            Stack newStack = new Stack();
            try {
                baselineStack = (Stack) getAppConfig()
                	.getObjectByType(baselineStack.getClass().getName());
                newStack = (Stack) getAppConfig()
                	.getObjectByType(newStack.getClass().getName());
            } 
            catch (EnterpriseConfigurationObjectException ecoe) {
                String errMsg = "An error occurred retrieving an object from " 
                	+ "AppConfig. The exception is: " + ecoe.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new CommandException(errMsg, ecoe);
            }
            if (eTestId != null)
                newStack.setTestId(testId);

            // Build the baseline and newdata states of the Stack.
            try {
                baselineStack.buildObjectFromInput(eBaselineData);
                newStack.buildObjectFromInput(eNewData);
                if (eTestId != null) { 
                	testId.buildObjectFromInput(eTestId);
                	newStack.setTestId(testId);
                }
                logger.info(LOGTAG + "TestId is: " + newStack.getTestId().toString());
            } catch (EnterpriseLayoutException ele) {
                String errMsg = "An error occurred building the baseline and newdata"
                        + " states of the Stack object passed in. " 
                		+ "The exception is: " + ele.getMessage();
                throw new CommandException(errMsg, ele);
            }

            // Perform the baseline check.
            
            // Get a configured StackQuerySpecification from
            // AppConfig.
            StackQuerySpecification querySpec = 
            	new StackQuerySpecification();
            try {
                querySpec = (StackQuerySpecification) getAppConfig()
                	.getObjectByType(querySpec.getClass().getName());
            } catch (EnterpriseConfigurationObjectException eoce) {
                String errMsg = "Error retrieving an object from AppConfig: "
                	+ "The exception" + "is: " + eoce.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new CommandException(errMsg);
            }
            
            // Set the value of the AccountId and StackId.
            try {
            	querySpec.setStackId(baselineStack.getStackId());
            }
            catch (EnterpriseFieldException efe) {
            	String errMsg = "An error occurred setting the field values " +
            		"of the Stack query specification. The exception is: " +
            		efe.getMessage();
            	logger.error(LOGTAG + errMsg);
            	throw new CommandException(errMsg, efe);
            }
            
            // Query for the Stack.
            Stack stack = null;
            try {
            	logger.info(LOGTAG + "Querying for baseline Stack...");
                List stackList = getProvider().query(querySpec);
                logger.info(LOGTAG + "Found " + stackList.size() + " result(s).");
                if (stackList.size() > 0) stack = (Stack)stackList.get(0);
            } catch (ProviderException pe) {
                // There was an error querying for the Stack.
                String errType = "application";
                String errCode = "AmazonAccountService-100X";
                String errDesc = "An error occurred querying the Stack provider" 
                		+ "to verify the baseline state of the Stack. " 
                		+ "The exception is: " + pe.getMessage();
                logger.error(LOGTAG + errDesc);
                logger.error("Message sent in is: \n" + getMessageBody(inDoc));
                ArrayList errors = new ArrayList();
                errors.add(buildError(errType, errCode, errDesc));
                String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
                return getMessage(msg, replyContents);
            }
           
            if (stack != null) {
                // Compare the retrieved baseline with the baseline in the
                // update request message.
                try {
                    if (baselineStack.equals(stack)) {
                        logger.info(LOGTAG + "Baseline matches the current " +
                        	"state of the Stack in AWS.");
                    } else {
                        logger.info(LOGTAG + "Baseline does not match the " +
                        	"current state of the Stack in AWS.");
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
                // Respond with an error, because no VPC matching the
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
                if (baselineStack.equals(newStack)) {
                    String errType = "application";
                    String errCode = "AwsAccountService-100X";
                    String errDesc = "Baseline state and new state of the "
                            + "stack are equal. No update operation may be performed.";
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
            
            // Update the Stack object using the Stack provider.
            try {
                long updateStartTime = System.currentTimeMillis();
                logger.info(LOGTAG + "Updating the Stack in the StackProvider...");
                getProvider().update(newStack);
                long updateTime = System.currentTimeMillis() - updateStartTime;
                logger.info(LOGTAG + "Stack update processed by StackProvider in " 
                		+ updateTime + " ms.");
            } catch (ProviderException pe) {
                // There was an error updating the Stack
                String errType = "application";
                String errCode = "MppiService-1002";
                String errDesc = "An error occurred updating the stack. The " + "exception is: " + pe.getMessage();
                logger.error(LOGTAG + errDesc);
                logger.error("Message sent in is: \n" + getMessageBody(inDoc));
                ArrayList errors = new ArrayList();
                errors.add(buildError(errType, errCode, errDesc));
                String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
                return getMessage(msg, replyContents);
            }
            logger.info(LOGTAG + "Updated Stack: " + newStack.toString());

            // Set the baseline on the new state of the identity.
            newStack.setBaseline(baselineStack);

            // Publish an Update-Sync Message
            try {
                MessageProducer producer = getProducerPool().getProducer();
                long publishStartTime = System.currentTimeMillis();
                if (getVerbose())
                    logger.info(LOGTAG + "Publishing VirtualPrivateCloud.Update-Sync message...");
                newStack.updateSync((SyncService) producer);
                long publishTime = System.currentTimeMillis() - publishStartTime;
                logger.info(LOGTAG + "Published Stack.Update-Sync message in "
                        + publishTime + " ms.");
            } catch (EnterpriseObjectSyncException eose) {
                String errMsg = "An error occurred publishing the Stack.Update-Sync " +
                		"message after updating the Stack. The exception is: " +
                	    eose.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new CommandException(errMsg, eose);
            } catch (JMSException jmse) {
                String errMsg = "An error occurred publishing the Stack.Update-Sync " +
                	"message after generating a stack. The "
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
 **/       
              
        // Handle a Delete-Request.
        if (msgAction.equalsIgnoreCase("Delete")) {
            logger.info(LOGTAG + "Handling a com.amazon.aws.CloudFormation." +
            	"Stack.Delete-Request message.");
            Element eStack = inDoc.getRootElement().getChild("DataArea")
            	.getChild("DeleteData").getChild("Stack");

            // Verify that the Stack element is not null. If it is
            // null, reply with an error.
            if (eStack == null) {
                String errType = "application";
                String errCode = "OpenEAI-1015";
                String errDesc = "Invalid element found in the Delete-Request "
                        + "message. This command expects a Stack";
                logger.error(LOGTAG + errDesc);
                logger.error("Message sent in is: \n" + getMessageBody(inDoc));
                ArrayList errors = new ArrayList();
                errors.add(buildError(errType, errCode, errDesc));
                String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
                return getMessage(msg, replyContents);
            }
            
            // Get a configured Stack from AppConfig.
            Stack stack = new Stack();
            try {
                stack = (Stack) getAppConfig()
                	.getObjectByType(stack.getClass().getName());
            } catch (EnterpriseConfigurationObjectException eoce) {
                String errMsg = "Error retrieving an object from AppConfig: The exception" + "is: " + eoce.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new CommandException(errMsg);
            }

            // Now build a stack object from the element in the message.
            try {
                stack.buildObjectFromInput(eStack);
                if (eTestId != null) testId.buildObjectFromInput(eTestId);
                stack.setTestId(testId);
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

            // Delete the Stack object using the provider.
            logger.info(LOGTAG + "Deleting a Stack...");

            try {
            	long deleteStartTime = System.currentTimeMillis();
                getProvider().delete(stack); 
                long deleteTime = System.currentTimeMillis() - deleteStartTime;
                logger.info(LOGTAG + "Deleted Stack in " + deleteTime + " ms.");
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
                    logger.info(LOGTAG + "Publishing Stack.Delete-Sync message...");
                stack.deleteSync("delete", (SyncService) producer);
                logger.info(LOGTAG + "Published Stack.Delete-Sync" + " message.");
            } catch (EnterpriseObjectSyncException eose) {
                String errMsg = "An error occurred publishing the Stack" 
                        + ".Delete-Sync message after deleting "
                		+ "the Stack. The " + "exception is: "
                        + eose.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new CommandException(errMsg, eose);
            } catch (JMSException jmse) {
            	String errMsg = "An error occurred publishing the Stack" 
                        + ".Delete-Sync message after deleting "
                		+ "the Stack. The " + "exception is: "
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
    protected void setProvider(StackProvider provider) {
        m_provider = provider;
    }

    /**
     * @return StackProvider, the Stack provider
     *         <P>
     *         Gets the Stack provider for this command.
     */
    protected StackProvider getProvider() {
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
