/* *****************************************************************************
 This file is part of the RHEDcloud AWS Account Service.

 Copyright 2020 RHEDcloud Foundation. All rights reserved.
 ******************************************************************************/

package edu.emory.awsaccount.service;

import edu.emory.awsaccount.service.provider.ProviderException;
import edu.emory.awsaccount.service.provider.TransitGatewayStatusProvider;
import edu.emory.moa.jmsobjects.network.v1_0.TransitGatewayStatus;
import edu.emory.moa.objects.resources.v1_0.TransitGatewayStatusQuerySpecification;
import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;
import org.jdom.Document;
import org.jdom.Element;
import org.openeai.config.CommandConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.LoggerConfig;
import org.openeai.config.PropertyConfig;
import org.openeai.jms.consumer.commands.CommandException;
import org.openeai.jms.consumer.commands.RequestCommand;
import org.openeai.layouts.EnterpriseLayoutException;
import org.openeai.moa.objects.resources.Error;

import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.TextMessage;
import java.util.ArrayList;
import java.util.List;

/**
 * This command handles requests for TransitGatewayStatus objects.
 * Specifically, it handles a Query-Request.
 */
public class TransitGatewayStatusRequestCommand extends AwsAccountRequestCommand implements RequestCommand {
    private static final String LOGTAG = "[TransitGatewayStatusRequestCommand] ";
    private static final Logger logger = Logger.getLogger(TransitGatewayStatusRequestCommand.class);
    private TransitGatewayStatusProvider m_provider = null;

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
    public TransitGatewayStatusRequestCommand(CommandConfig cConfig) throws InstantiationException {
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
        String className = getProperties().getProperty("transitGatewayStatusProviderClassName");
        if (className == null || className.equals("")) {
            String errMsg = "No transitGatewayStatusProviderClassName property specified. Can't continue.";
            logger.fatal(LOGTAG + errMsg);
            throw new InstantiationException(errMsg);
        }

        TransitGatewayStatusProvider provider;
        try {
            logger.info(LOGTAG + "Getting class for transitGatewayStatusProviderClassName: " + className);
            provider = (TransitGatewayStatusProvider) Class.forName(className).newInstance();
            logger.info(LOGTAG + "Initializing TransitGatewayStatusProvider: " + provider.getClass().getName());
            provider.init(getAppConfig());
            logger.info(LOGTAG + "TransitGatewayStatusProvider initialized.");
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
            String errMsg = "An error occurred initializing " + className + ". The exception is: " + e.getMessage();
            logger.fatal(LOGTAG + errMsg);
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
     * a TransitGatewayStatus object and the action is a query.
     * Then this method uses the configured provider to perform each operation.
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
        // TransitGatewayStatus object; if not, reply with an error.
        if (!msgObject.equalsIgnoreCase("TransitGatewayStatus")) {
            String errType = "application";
            String errCode = "OpenEAI-TransitGatewayStatus-1001";
            String errDesc = "Unsupported message object: " + msgObject + ". This command expects 'TransitGatewayStatus'.";
            logger.error(LOGTAG + errDesc);
            logger.error(LOGTAG + "Message sent in is: \n" + getMessageBody(inDoc));
            ArrayList<Error> errors = new ArrayList<>();
            errors.add(buildError(errType, errCode, errDesc));
            String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
            return getMessage(msg, replyContents);
        }

        // Handle a Query-Request.
        if (msgAction.equalsIgnoreCase("Query")) {
            logger.info(LOGTAG + "Handling an edu.emory.Network.TransitGatewayStatus.Query-Request message.");
            Element eQuerySpec = inDoc.getRootElement().getChild("DataArea").getChild("TransitGatewayStatusQuerySpecification");

            // Get a configured query object from AppConfig.
            TransitGatewayStatusQuerySpecification querySpec;
            try {
                querySpec = (TransitGatewayStatusQuerySpecification) getAppConfig().getObjectByType(TransitGatewayStatusQuerySpecification.class.getName());
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
                    String errCode = "AwsAccountService-TransitGatewayStatus-1004";
                    String errDesc = "An error occurred building the query object from the DataArea element in the Query-Request message. The exception is: " + e.getMessage();
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
                String errCode = "AwsAccountService-TransitGatewayStatus-1005";
                String errDesc = "An error occurred building the query object from the DataArea element in the Query-Request message. The query spec is null.";
                logger.error(LOGTAG + errDesc);
                logger.error("Message sent in is: \n" + getMessageBody(inDoc));
                ArrayList<Error> errors = new ArrayList<>();
                errors.add(buildError(errType, errCode, errDesc));
                String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
                return getMessage(msg, replyContents);
            }

            logger.info(LOGTAG + "Querying for the TransitGatewayStatus results");

            List<TransitGatewayStatus> results;
            try {
            	long elapsedStartTime = System.currentTimeMillis();
                results = getProvider().query(querySpec);
                long elapsedTime = System.currentTimeMillis() - elapsedStartTime;
                logger.info(LOGTAG + "Queried for TransitGatewayStatus in " + elapsedTime + "ms.");
            }
            catch (ProviderException e) {
                // There was an error generating the identity
                String errType = "application";
                String errCode = "AwsAccountService-TransitGatewayStatus-1006";
                String errDesc = "An error occurred querying for the TransitGatewayStatus. The exception is: " + e.getMessage();
                logger.error(LOGTAG + errDesc);
                logger.error("Message sent in is: \n" + getMessageBody(inDoc));
                ArrayList<Error> errors = new ArrayList<>();
                errors.add(buildError(errType, errCode, errDesc));
                String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
                return getMessage(msg, replyContents);
            }

            // Prepare the response.
            localProvideDoc.getRootElement().getChild("DataArea").removeContent();
            // If there are results, place them in the response.
            if (results == null) {
                logger.info(LOGTAG + "Results are null; no matching TransitGatewayStatus found.");
            }
            else if (results.size() == 0) {
                logger.info(LOGTAG + "Results are empty; no matching TransitGatewayStatus found.");
            }
            else {
                logger.info(LOGTAG + "Found " + results.size() + " matching result(s).");

                List<Element> elements = new ArrayList<>(results.size());
                for (TransitGatewayStatus result : results) {
                    try {
                        elements.add((Element) result.buildOutputFromObject());
                    } catch (EnterpriseLayoutException e) {
                        String errMsg = "An error occurred serializing TransitGatewayStatus object to an XML element. The exception is: " + e.getMessage();
                        logger.error(LOGTAG + errMsg);
                        throw new CommandException(errMsg, e);
                    }
                }
                localProvideDoc.getRootElement().getChild("DataArea").addContent(elements);
            }
            String replyContents = buildReplyDocument(eControlArea, localProvideDoc);

            // Log execution time.
            long executionTime = System.currentTimeMillis() - startTime;
            logger.info(LOGTAG + "Query-Request command execution complete in " + executionTime + " ms. with reply " + replyContents);

            // Return the response with status success.
            return getMessage(msg, replyContents);
        }
        else {
            // The messageAction is invalid; it is not a query, generate, create. update, or delete
            String errType = "application";
            String errCode = "OpenEAI-TransitGatewayStatus-1008";
            String errDesc = "Unsupported message action: " + msgAction + ". This command only supports query.";
            logger.fatal(LOGTAG + errDesc);
            logger.fatal("Message sent in is: \n" + getMessageBody(inDoc));
            ArrayList<Error> errors = new ArrayList<>();
            errors.add(buildError(errType, errCode, errDesc));
            String replyContents = buildReplyDocumentWithErrors(eControlArea, localResponseDoc, errors);
            return getMessage(msg, replyContents);
        }
    }

    /**
     * Sets the provider for this command.
     * @param provider the provider
     */
    protected void setProvider(TransitGatewayStatusProvider provider) {
        m_provider = provider;
    }

    /**
     * Gets the provider for this command.
     * @return the provider
     */
    protected TransitGatewayStatusProvider getProvider() {
        return m_provider;
    }
}
