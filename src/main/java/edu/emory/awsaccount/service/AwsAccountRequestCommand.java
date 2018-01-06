/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2016 Emory University. All rights reserved. 
 ******************************************************************************/

package edu.emory.awsaccount.service;

// Log4j
import org.apache.log4j.*;

// JDOM
import org.jdom.Document;

//OpenEAI foundation components
import org.openeai.OpenEaiObject;
import org.openeai.config.CommandConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.PropertyConfig;
import org.openeai.jms.consumer.commands.*;
import org.openeai.xml.XmlDocumentReader;
import org.openeai.xml.XmlDocumentReaderException;

/**
 * This abstract command provides some functions common to all commands used by
 * the AWS Account service. 
 * 
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 5 June 2016
 */
public abstract class AwsAccountRequestCommand extends RequestCommandImpl implements
		RequestCommand {
	protected Category logger = OpenEaiObject.logger;
	protected Document m_responseDoc = null; // the primed XML response document
	protected Document m_provideDoc = null; // the primed XML response document
	private static String LOGTAG = "[AwsAccountRequestCommand] ";
	protected boolean m_verbose = false;

	/**
	 * @param CommandConfig
	 * @throws InstantiationException
	 * <P>
	 * This constructor initializes the command using a CommandConfig object. It
	 * invokes the constructor of the ancestor, RequestCommandImpl, and then
	 * retrieves one PropertyConfig object from AppConfig by name and gets and
	 * sets the command properties using that PropertyConfig object. This means
	 * that this command must have one PropertyConfig object in its configuration
	 * named 'GeneralProperties'. This constructor also initializes the response
	 * document and provide document used in replies.
	 */
	public AwsAccountRequestCommand(CommandConfig cConfig) throws InstantiationException {
		super(cConfig);	
		logger.info(LOGTAG + "Initializing " + edu.emory.awsaccount.service.ReleaseTag.getReleaseInfo());
		
		// Get and set the general properties for this command.
		PropertyConfig pConfig = new PropertyConfig();
		try {
			pConfig = (PropertyConfig) getAppConfig().getObject("GeneralProperties");
		} 
		catch (EnterpriseConfigurationObjectException eoce) {
			String errMsg = "Error retrieving a PropertyConfig object from "
					+ "AppConfig: The exception is: " + eoce.getMessage();
			logger.fatal(LOGTAG + errMsg);
			throw new InstantiationException(errMsg);
		}
		setProperties(pConfig.getProperties());

		// Get the verbose property.
		String verbose = getProperties().getProperty("verbose", "false");
		setVerbose(Boolean.getBoolean(verbose));
		logger.info(LOGTAG + "property verbose: " + getVerbose());

		// Initialize response documents.
		XmlDocumentReader xmlReader = new XmlDocumentReader();
		try {
			Document responseDoc = xmlReader.initializeDocument(getProperties()
				.getProperty("responseDocumentUri"), getOutboundXmlValidation());
			if (responseDoc == null) {
				String errMsg = "Missing 'responseDocumentUri' "
						+ "property in the deployment descriptor.  Can't continue.";
				logger.fatal(LOGTAG + errMsg);
				throw new InstantiationException(errMsg);
			}
			setResponseDocument(responseDoc);
			
			Document provideDoc = xmlReader.initializeDocument(getProperties()
					.getProperty("provideDocumentUri"), getOutboundXmlValidation());
				if (provideDoc == null) {
					String errMsg = "Missing 'provideDocumentUri' "
							+ "property in the deployment descriptor.  Can't continue.";
					logger.fatal(LOGTAG + errMsg);
					throw new InstantiationException(errMsg);
				}
			setProvideDocument(provideDoc);
			
		} 
		catch (XmlDocumentReaderException xdre) {
			String errMsg = "An error occurred initializing the primed reponse " +
			  "document. The exception is: " + xdre.getMessage();
			logger.fatal(LOGTAG + errMsg);
			throw new InstantiationException(xdre.getMessage());
		}
		
		logger.info(LOGTAG + "instantiated successfully.");
	}
	
	/**
	 * @param boolean, the verbose parameter
	 * <P>
	 * Set a parameter to toggle verbose logging.
	 */
	protected void setVerbose(boolean b) {
		m_verbose = b;
	}
	
	/**
	 * @return boolean, the verbose parameter
	 * <P>
	 * Gets the value of the verbose logging parameter.
	 */
	protected boolean getVerbose() {
		return m_verbose;   
	}
	
	/**
	 * @param Document, the response document
	 * <P>
	 * Set a primed XML response document the command will use to reply to the
	 * requests it handles.
	 */
	protected void setResponseDocument(Document d) {
		m_responseDoc = d;
	}
	
	/**
	 * @return Document, the response document
	 * <P>
	 * Gets the primed XML response document the command will use to reply to the
	 * requests it handles.
	 */
	protected Document getResponseDocument() {
		return m_responseDoc;   
	}
	
	/**
	 * @param Document, the response document
	 * <P>
	 * Set a primed XML response document the command will use to reply to the
	 * requests it handles.
	 */
	protected void setProvideDocument(Document d) {
		m_provideDoc = d;
	}
	
	/**
	 * @return Document, the response document
	 * <P>
	 * Gets the primed XML response document the command will use to reply to the
	 * requests it handles.
	 */
	protected Document getProvideDocument() {
		return m_provideDoc;   
	}
}