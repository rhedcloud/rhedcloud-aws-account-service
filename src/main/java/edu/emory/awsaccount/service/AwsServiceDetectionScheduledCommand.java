/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2018 Emory University. All rights reserved. 
 ******************************************************************************/


package edu.emory.awsaccount.service;

import java.util.List;
import java.util.ListIterator;
import java.util.Properties;

import org.apache.log4j.Logger;
import org.openeai.afa.ScheduledCommand;
import org.openeai.afa.ScheduledCommandException;
import org.openeai.afa.ScheduledCommandImpl;
import org.openeai.config.CommandConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.PropertyConfig;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.identitymanagement.AmazonIdentityManagement;
import com.amazonaws.services.identitymanagement.AmazonIdentityManagementClientBuilder;
import com.amazonaws.services.identitymanagement.model.AmazonIdentityManagementException;
import com.amazonaws.services.identitymanagement.model.ListAccountAliasesResult;
import com.amazonaws.services.support.AWSSupport;
import com.amazonaws.services.support.AWSSupportClient;
import com.amazonaws.services.support.AWSSupportClientBuilder;
import com.amazonaws.services.support.model.AWSSupportException;
import com.amazonaws.services.support.model.DescribeServicesRequest;
import com.amazonaws.services.support.model.DescribeServicesResult;
import com.amazonaws.services.support.model.Service;

import edu.emory.awsaccount.service.provider.ProviderException;

/**
 * This command interrogate
 * 
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 4 July 2018
 * 
 */
public class AwsServiceDetectionScheduledCommand extends AwsAccountScheduledCommand implements ScheduledCommand {
   
	private String LOGTAG = "[AwsServiceDetectionScheduledCommand] ";
    private String m_accessKeyId = null;
    private String m_secretKey = null;

    public AwsServiceDetectionScheduledCommand(CommandConfig cConfig) throws InstantiationException {
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
            String errMsg = "Error retrieving a PropertyConfig object from "
            	+ "AppConfig: The exception is: " + eoce.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new InstantiationException(errMsg);
        }
        
        // Get the verbose property.
        String verbose = getProperties().getProperty("verbose", "false");
        setVerbose(Boolean.getBoolean(verbose));
        logger.info(LOGTAG + "property verbose: " + getVerbose());
        
        // Get the AWS credentials the provider will use
        String accessKeyId = getProperties().getProperty("accessKeyId");
        if (accessKeyId == null || accessKeyId.equals("")) {
            String errMsg = "No base accessKeyId property specified. Can't continue.";
            throw new InstantiationException(errMsg);
        }
        setAccessKeyId(accessKeyId);

        String secretKey = getProperties().getProperty("secretKey");
        if (accessKeyId == null || accessKeyId.equals("")) {
            String errMsg = "No base secretKey property specified. Can't continue.";
            throw new InstantiationException(errMsg);
        }
        setSecretKey(secretKey);
        
        // Create a connection to the master account and query
        // for all AWS services to demonstrate basic functionality.
        // Instantiate a basic credential provider
        logger.info(LOGTAG + "Initializing AWS credential provider...");
        BasicAWSCredentials creds = new BasicAWSCredentials(accessKeyId, secretKey);
        AWSStaticCredentialsProvider cp = new AWSStaticCredentialsProvider(creds);

        // Create the Support client
        logger.info(LOGTAG + "Creating the Support client...");
        AWSSupport support = AWSSupportClientBuilder.standard().withRegion("us-east-1").withCredentials(cp).build();

        // Query for the list of AWS services to make sure all is working.
        DescribeServicesResult result = null;
        try {
            logger.info(LOGTAG + "Querying for AWS services...");
            long startTime = System.currentTimeMillis();
            DescribeServicesRequest request = new DescribeServicesRequest();
            result = support.describeServices(request);
            long time = System.currentTimeMillis() - startTime;
            logger.info(LOGTAG + "Retrieved service list in " + time + " ms.");
        } catch (AWSSupportException ase) {
            String errMsg = "An error occured querying for a list of services. " +
            	"The exception is: " + ase.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new InstantiationException(errMsg);
        }

        List<Service> serviceList = result.getServices();
        ListIterator it = serviceList.listIterator();
        logger.info(LOGTAG + "There are presently " + serviceList.size() + " services.");
        int i=0;
        while (it.hasNext()) {
            Service service = (Service) it.next();
            logger.info(LOGTAG + "Service number " + ++i + 
            	" is: " + service.getName() + " (" + service.getCode() + ")");
        }

        logger.info(LOGTAG + " Initialization complete.");
    }

    @Override
    public int execute() throws ScheduledCommandException {
    	String LOGTAG = "[AwsServiceDetectionScheduledCommand.execute] ";
        long executionStartTime = System.currentTimeMillis();
        logger.info(LOGTAG + "Executing ...");
        
        // Query AWS with the Support API for the master list of services.
        
        
        // Query the AWS Account Service for Emory's master list of services.
        
        // Iterate over the list of official AWS Services. If any of these 
        // do not exist in Emory's master list, add them to the list of
        // new services.
        
        // Iterate over the master list of Emory services. If any of these
        // do not exist in the AWS master list, add them to the list of 
        // deprecated services.
        
        // Iterate over the list of new services and send a
        // Service.Create-Request for each one.
        
        // Iterate over the list of deprecated services and send 
        // a Service.Update-Request to change the AWS status of
        // the service to deprecated.
        
        long executionTime = System.currentTimeMillis() - executionStartTime;
        logger.info(LOGTAG + "Command execution completed in " + executionTime + " ms.");
        
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
    
    /**
     * 
     * @param String,
     *            the AWS access key ID to use in client connections
     * 
     */
    private void setAccessKeyId(String accessKeyId) {
        m_accessKeyId = accessKeyId;
    }

    /**
     * 
     * @return String, the AWS access key ID to use in client connections
     * 
     */
    private String getAccessKeyId() {
        return m_accessKeyId;
    }

    /**
     * 
     * @param String,
     *            the AWS secret key to use in client connections
     * 
     */
    private void setSecretKey(String secretKey) {
        m_secretKey = secretKey;
    }

    /**
     * 
     * @return String, the AWS secret to use in client connections
     * 
     */
    private String getSecretKey() {
        return m_secretKey;
    }
}
