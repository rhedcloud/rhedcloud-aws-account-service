/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2018 Emory University. All rights reserved. 
 ******************************************************************************/


package edu.emory.awsaccount.service;

import java.util.Properties;

import org.apache.log4j.Logger;
import org.openeai.afa.ScheduledCommand;
import org.openeai.afa.ScheduledCommandException;
import org.openeai.afa.ScheduledCommandImpl;
import org.openeai.config.CommandConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.PropertyConfig;

/**
 * This command interrogate
 * 
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 4 July 2018
 * 
 */
public class AwsAccountScheduledCommand extends ScheduledCommandImpl implements ScheduledCommand {
    private String LOGTAG = "[AwsAccountScheduledCommand] ";
    protected static final Logger logger = Logger.getLogger(AwsAccountScheduledCommand.class);
    protected boolean m_verbose = false;


    public AwsAccountScheduledCommand(CommandConfig cConfig) throws InstantiationException {
        super(cConfig);
        logger.info(LOGTAG + " Initializing ...");

        // Get the command properties
        PropertyConfig pConfig = new PropertyConfig();
        try {
            pConfig = (PropertyConfig)getAppConfig().getObject("GeneralProperties");
            Properties props = pConfig.getProperties();
            setProperties(props);
        } 
        catch (EnterpriseConfigurationObjectException eoce) {
            String errMsg = "Error retrieving a PropertyConfig object from " + "AppConfig: The exception is: " + eoce.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new InstantiationException(errMsg);
        }
        
        // Get the verbose property.
        String verbose = getProperties().getProperty("verbose", "false");
        setVerbose(Boolean.getBoolean(verbose));
        logger.info(LOGTAG + "property verbose: " + getVerbose());

        logger.info(LOGTAG + " Initialization complete.");
    }

    @Override
    public int execute() throws ScheduledCommandException {
        long start = System.currentTimeMillis();
        logger.info(LOGTAG + "Executing ...");
        
        return 0;
    }
    
    /**
     * @param boolean,
     *            the verbose parameter
     *            <P>
     *            Set a parameter to toggle verbose logging.
     */
    protected void setVerbose(boolean b) {
        m_verbose = b;
    }

    /**
     * @return boolean, the verbose parameter
     *         <P>
     *         Gets the value of the verbose logging parameter.
     */
    protected boolean getVerbose() {
        return m_verbose;
    }
}
