/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2018 Emory University. All rights reserved. 
 ******************************************************************************/

package edu.emory.awsaccount.service;

// Log4j
import org.apache.log4j.*;

//OpenEAI foundation components
import org.openeai.OpenEaiObject;
import org.openeai.config.CommandConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.LoggerConfig;
import org.openeai.config.PropertyConfig;
import org.openeai.jms.consumer.commands.*;

/**
 * This abstract command provides some functions common to all sync commands used by
 * the AWS Account service. 
 * 
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 4 July 2018
 */
public abstract class AwsAccountSyncCommand extends SyncCommandImpl implements
		SyncCommand {
	protected Category logger = OpenEaiObject.logger;
	private static String LOGTAG = "[AwsAccountSyncCommand] ";
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
	public AwsAccountSyncCommand(CommandConfig cConfig) throws InstantiationException {
		super(cConfig);	
		logger.info(LOGTAG + "Initializing " + edu.emory.awsaccount.service.ReleaseTag.getReleaseInfo());
		
		// Configure a command-specific logger if one exists.
		try {
			logger.info(LOGTAG
					+ "Configuring a logger for the command. If no logger "
					+ "configuration is specified with this command, the command will set "
					+ "its logger to be that of org.openeai.OpenEaiObject.");
			LoggerConfig lConfig = new LoggerConfig();
			lConfig = (LoggerConfig) getAppConfig().getObjectByType(
					lConfig.getClass().getName());
			logger = Category.getInstance(getClass().getName());
			PropertyConfigurator.configure(lConfig.getProperties());
			logger.info(LOGTAG + "Configured command-specific logger.");
		} catch (EnterpriseConfigurationObjectException ecoe) {
			logger.warn(LOGTAG
					+ "There was an error configuring the logger or a "
					+ "logger configuration for this command does not exist, setting "
					+ "logger to be org.openeai.OpenEaiObject.logger.");
			logger = org.openeai.OpenEaiObject.logger;
		}
		
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

}