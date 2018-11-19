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

import javax.jms.JMSException;

import org.apache.log4j.Logger;
import org.openeai.afa.ScheduledCommand;
import org.openeai.afa.ScheduledCommandException;
import org.openeai.config.CommandConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.EnterpriseFieldException;
import org.openeai.config.PropertyConfig;
import org.openeai.jms.producer.PointToPointProducer;
import org.openeai.jms.producer.ProducerPool;
import org.openeai.moa.EnterpriseObjectCreateException;
import org.openeai.moa.EnterpriseObjectQueryException;
import org.openeai.moa.XmlEnterpriseObjectException;
import org.openeai.moa.objects.resources.Result;
import org.openeai.transport.RequestService;

import com.amazon.aws.moa.objects.resources.v1_0.Datetime;
import com.amazon.aws.moa.objects.resources.v1_0.ServiceQuerySpecification;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.support.AWSSupport;
import com.amazonaws.services.support.AWSSupportClientBuilder;
import com.amazonaws.services.support.model.AWSSupportException;
import com.amazonaws.services.support.model.Category;
import com.amazonaws.services.support.model.DescribeServicesRequest;
import com.amazonaws.services.support.model.DescribeServicesResult;
import com.amazonaws.services.support.model.Service;

/**
 * This command interrogate
 * 
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 4 July 2018
 * 
 */
public class AwsServiceDetectionScheduledCommand extends AwsAccountScheduledCommand implements ScheduledCommand {
    private static Logger logger = Logger.getLogger(AwsServiceDetectionScheduledCommand.class);
    private String LOGTAG = "[AwsServiceDetectionScheduledCommand] ";
    private String m_accessKeyId = null;
    private String m_secretKey = null;
    private ProducerPool m_awsAccountServiceProducerPool = null;
    private final static String ACTIVE_AWS_SERVICE_STATUS = "active";
    private final static String DEPRECATED_AWS_SERVICE_STATUS = "deprecated";
    private final static String BLOCKED_PENDING_REVIEW_SITE_SERVICE_STATUS = "Blocked Pending Review";
    private final static String DEFAULT_SERVICE_URL = "https://aws.amazon.com/products/";
    private final static String DEFAULT_AWS_HIPAA_ELIGIBLE = "false";
    private final static String DEFAULT_SITE_HIPAA_ELIGIBLE = "false";
    private final static String DEFAULT_CREATE_USER = "AwsAccountService";

    public AwsServiceDetectionScheduledCommand(CommandConfig cConfig) throws InstantiationException {
        super(cConfig);
        logger.info(LOGTAG + " Initializing ...");
        // Get the command properties
        PropertyConfig pConfig = new PropertyConfig();
        try {
            pConfig = (PropertyConfig) getAppConfig().getObject("GeneralProperties");
            Properties props = pConfig.getProperties();
            setProperties(props);
        } catch (EnterpriseConfigurationObjectException eoce) {
            String errMsg = "Error retrieving a PropertyConfig object from " + "AppConfig: The exception is: " + eoce.getMessage();
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
        if (secretKey == null || secretKey.equals("")) {
            String errMsg = "No base secretKey property specified. Can't continue.";
            throw new InstantiationException(errMsg);
        }
        setSecretKey(secretKey);

        // This provider needs to send messages to the AWS account service
        // to create UserNotifications.
        ProducerPool p2p1 = null;
        try {
            p2p1 = (ProducerPool) getAppConfig().getObject("AwsAccountServiceProducerPool");
            setAwsAccountServiceProducerPool(p2p1);
        } catch (EnterpriseConfigurationObjectException ecoe) {
            // An error occurred retrieving an object from AppConfig. Log it
            // and
            // throw an exception.
            String errMsg = "An error occurred retrieving an object from " + "AppConfig. The exception is: " + ecoe.getMessage();
            logger.fatal(LOGTAG + errMsg);
            throw new InstantiationException(errMsg);
        }
        logger.info(LOGTAG + "load service...");
        // Get a configured MOA Service and ServiceQuerySpecification object
        // from AppConfig
        com.amazon.aws.moa.jmsobjects.services.v1_0.Service aeoService = new com.amazon.aws.moa.jmsobjects.services.v1_0.Service();
        ServiceQuerySpecification xeoQuerySpec = new ServiceQuerySpecification();
        try {
            aeoService = (com.amazon.aws.moa.jmsobjects.services.v1_0.Service) getAppConfig()
                    .getObjectByType(aeoService.getClass().getName());
            xeoQuerySpec = (ServiceQuerySpecification) getAppConfig().getObjectByType(xeoQuerySpec.getClass().getName());
        } catch (EnterpriseConfigurationObjectException ecoe) {
            String errMsg = "An error occurred getting an object from " + "AppConfig. The exception is: " + ecoe.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new InstantiationException(errMsg);
        }

        // Create a connection to the master account and query
        // for all AWS services to demonstrate basic functionality.
        // Instantiate a basic credential provider
        logger.info(LOGTAG + "Initializing AWS credential provider...");
        BasicAWSCredentials creds = new BasicAWSCredentials(getAccessKeyId(), getSecretKey());
        AWSStaticCredentialsProvider cp = new AWSStaticCredentialsProvider(creds);

        // Create the Support client
        logger.info(LOGTAG + "Creating the Support client...");
        AWSSupport support = AWSSupportClientBuilder.standard().withCredentials(cp).build();

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
            String errMsg = "An error occured querying for a list of services. " + "The exception is: " + ase.getMessage();
            logger.error(LOGTAG + errMsg, ase);
            throw new InstantiationException(errMsg);
        }

        List<Service> serviceList = result.getServices();
        ListIterator it = serviceList.listIterator();
        logger.info(LOGTAG + "There are presently " + serviceList.size() + " services.");
        int i = 0;
        while (it.hasNext()) {
            Service service = (Service) it.next();
            logger.info(LOGTAG + "Service number " + ++i + " is: " + service.getName() + " (" + service.getCode() + ")");
        }

        logger.info(LOGTAG + " Initialization complete.");
    }

    @Override
    public int execute() throws ScheduledCommandException {
        String LOGTAG = "[AwsServiceDetectionScheduledCommand.execute] ";
        long executionStartTime = System.currentTimeMillis();
        logger.info(LOGTAG + "Executing ...");

        // Query AWS with the Support API for the master list of services.
        // Create a connection to the master account and query
        // for all AWS services to demonstrate basic functionality.
        // Instantiate a basic credential provider
        logger.info(LOGTAG + "Initializing AWS credential provider...");
        BasicAWSCredentials creds = new BasicAWSCredentials(getAccessKeyId(), getSecretKey());
        AWSStaticCredentialsProvider cp = new AWSStaticCredentialsProvider(creds);

        // Create the Support client
        logger.info(LOGTAG + "Creating the Support client...");
        AWSSupport support = AWSSupportClientBuilder.standard().withCredentials(cp).build();

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
            String errMsg = "An error occured querying for a list of services. " + "The exception is: " + ase.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ScheduledCommandException(errMsg, ase);
        }

        List<Service> serviceList = result.getServices();
        logger.info(LOGTAG + "There are presently " + serviceList.size() + " services.");
        if (getVerbose()) {
            ListIterator it = serviceList.listIterator();
            int i = 0;
            while (it.hasNext()) {
                Service service = (Service) it.next();
                logger.info(LOGTAG + "Service number " + ++i + " is: " + service.getName() + " (" + service.getCode() + ")");
            }
        }

        // For each service in the AWS master list, query for a corresponding
        // service in the Emory registry. If there is not one, create it.
        // If there is one, update it.
        ListIterator it = serviceList.listIterator();
        while (it.hasNext()) {
            com.amazonaws.services.support.model.Service service = (com.amazonaws.services.support.model.Service) it.next();

            // Query for the AWS Service in the registry
            com.amazon.aws.moa.jmsobjects.services.v1_0.Service aeoService = queryService(service.getCode());

            // If there is no service found in the AWS Account Service, create
            // it.
            if (aeoService == null) {
                logger.info(LOGTAG + "No service found in AWS Account Service " + "for the AWS Support Service: " + service.getName() + " ("
                        + service.getCode() + "). Creating a new service in the AWS " + "Account Service.");
                com.amazon.aws.moa.jmsobjects.services.v1_0.Service newAeoService = buildAeoServiceFromAwsService(service);

                createService(newAeoService);

                String xmlService = null;
                try {
                    xmlService = newAeoService.toXmlString();
                } catch (XmlEnterpriseObjectException xeoe) {
                    String errMsg = "An error occurred serializing a " + "Service object to an XML string. The exception is: "
                            + xeoe.getMessage();
                    logger.error(LOGTAG + errMsg);
                    throw new ScheduledCommandException(errMsg, xeoe);
                }

                logger.info(LOGTAG + "Created new service: " + xmlService);
            }

            // If there is a service found in the AWS Account Service, determine
            // if an update is required.
            if (aeoService != null) {
                if (isServiceUpdateRequired()) {
                    logger.info(LOGTAG + "Service found in AWS Account Service " + "for the AWS Support Service: " + service.getName()
                            + " (" + service.getCode() + "). Updating existing service in the AWS " + "Account Service.");
                    // aeoService = buildNewServiceState(aeoService, service);
                    // updateService(aeoService);
                } else {
                    logger.info(LOGTAG + "Service found in AWS Account Service " + "for the AWS Support Service: " + service.getName()
                            + " (" + service.getCode() + "). No update required. ");
                }
            }
        }

        // Iterate over the master list of Emory services. If any of these
        // do not exist in the AWS master list, add them to the list of
        // deprecated services.
        List<com.amazon.aws.moa.jmsobjects.services.v1_0.Service> awsAccountServiceList = queryServices();

        logger.info(LOGTAG + "Found " + awsAccountServiceList.size() + " services in the AWS Account Service registry.");

        // Iterate over the list of deprecated services, updating each
        // to have a status of deprecated.
        ListIterator awsAccountServiceListIterator = awsAccountServiceList.listIterator();
        while (awsAccountServiceListIterator.hasNext()) {
            com.amazon.aws.moa.jmsobjects.services.v1_0.Service awsAccountService = (com.amazon.aws.moa.jmsobjects.services.v1_0.Service) awsAccountServiceListIterator
                    .next();
            if (isInAwsServiceList(awsAccountService.getAwsServiceCode(), serviceList) == false) {
                logger.info("Service named " + awsAccountService.getAwsServiceName() + " with code " + awsAccountService.getAwsServiceName()
                        + " was " + " not found in the AWS master service list. Updating this " + "service to have deprecated status.");
                try {
                    awsAccountService.setAwsStatus(DEPRECATED_AWS_SERVICE_STATUS);
                } catch (EnterpriseFieldException efe) {
                    String errMsg = "An error occurred setting the field " + "of the Service. The exception is: " + efe.getMessage();
                    logger.error(LOGTAG + errMsg);
                    throw new ScheduledCommandException(errMsg, efe);
                }
                // updateService(awsAccountService);
            }
        }

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
    @Override
    protected void setVerbose(boolean b) {
        m_verbose = b;
    }

    /**
     * @return boolean, the verbose parameter
     *         <P>
     *         Gets the value of the verbose logging parameter.
     */
    @Override
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

    private void setAwsAccountServiceProducerPool(ProducerPool pool) {
        m_awsAccountServiceProducerPool = pool;
    }

    private ProducerPool getAwsAccountServiceProducerPool() {
        return m_awsAccountServiceProducerPool;
    }

    private com.amazon.aws.moa.jmsobjects.services.v1_0.Service queryService(String awsServiceCode) throws ScheduledCommandException {

        String LOGTAG = "[AwsServiceDetectionScheduledCommand.queryService] ";

        if (awsServiceCode == null || awsServiceCode.equals("")) {
            String errMsg = "No ServiceCode provided. Can't continue.";
            logger.error(errMsg);
            throw new ScheduledCommandException(errMsg);
        }

        // Get a configured MOA Service and ServiceQuerySpecification object
        // from AppConfig
        com.amazon.aws.moa.jmsobjects.services.v1_0.Service service = new com.amazon.aws.moa.jmsobjects.services.v1_0.Service();
        ServiceQuerySpecification querySpec = new ServiceQuerySpecification();
        try {
            service = (com.amazon.aws.moa.jmsobjects.services.v1_0.Service) getAppConfig().getObjectByType(service.getClass().getName());
            querySpec = (ServiceQuerySpecification) getAppConfig().getObjectByType(querySpec.getClass().getName());
        } catch (EnterpriseConfigurationObjectException ecoe) {
            String errMsg = "An error occurred getting an object from " + "AppConfig. The exception is: " + ecoe.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ScheduledCommandException(errMsg, ecoe);
        }

        // Get a RequestService to use for this transaction.
        RequestService rs = null;
        try {
            rs = (RequestService) getAwsAccountServiceProducerPool().getExclusiveProducer();
        } catch (JMSException jmse) {
            String errMsg = "An error occurred getting a request service to use " + "in this transaction. The exception is: "
                    + jmse.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ScheduledCommandException(errMsg, jmse);
        }

        // Set the values of the QuerySpec.
        try {
            querySpec.setAwsServiceCode(awsServiceCode);
        } catch (EnterpriseFieldException efe) {
            String errMsg = "An error occurred setting field values on the " + "object. The exception is: " + efe.getMessage();
            logger.error(errMsg);
            throw new ScheduledCommandException(errMsg, efe);
        }

        // Query for the Service object.
        List<com.amazon.aws.moa.jmsobjects.services.v1_0.Service> results = null;
        try {
            long startTime = System.currentTimeMillis();
            results = service.query(querySpec, rs);
            long time = System.currentTimeMillis() - startTime;
            logger.info(LOGTAG + "Queried the AWS Account Service for " + "Service object in " + time + " ms.");
        } catch (EnterpriseObjectQueryException eoqe) {
            String errMsg = "An error occurred querying for the " + "Service object The exception is: " + eoqe.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ScheduledCommandException(errMsg, eoqe);
        }

        // In any case, release the producer back to the pool.
        finally {
            getAwsAccountServiceProducerPool().releaseProducer((PointToPointProducer) rs);
        }

        com.amazon.aws.moa.jmsobjects.services.v1_0.Service resultService = null;

        if (results.size() == 1) {
            resultService = results.get(0);
        }

        if (results.size() > 1) {
            String errMsg = "Unexpected results. More than one service was " + "returned for ServiceCode " + awsServiceCode + ".";
            logger.error(LOGTAG + errMsg);
            throw new ScheduledCommandException(errMsg);
        }

        return resultService;
    }

    private List<com.amazon.aws.moa.jmsobjects.services.v1_0.Service> queryServices() throws ScheduledCommandException {

        String LOGTAG = "[AwsServiceDetectionScheduledCommand.queryServices] ";

        // Get a configured MOA Service and ServiceQuerySpecification object
        // from AppConfig
        com.amazon.aws.moa.jmsobjects.services.v1_0.Service service = new com.amazon.aws.moa.jmsobjects.services.v1_0.Service();
        ServiceQuerySpecification querySpec = new ServiceQuerySpecification();
        try {
            service = (com.amazon.aws.moa.jmsobjects.services.v1_0.Service) getAppConfig().getObjectByType(service.getClass().getName());
            querySpec = (ServiceQuerySpecification) getAppConfig().getObjectByType(querySpec.getClass().getName());
        } catch (EnterpriseConfigurationObjectException ecoe) {
            String errMsg = "An error occurred getting an object from " + "AppConfig. The exception is: " + ecoe.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ScheduledCommandException(errMsg, ecoe);
        }

        // Get a RequestService to use for this transaction.
        RequestService rs = null;
        try {
            rs = (RequestService) getAwsAccountServiceProducerPool().getExclusiveProducer();
        } catch (JMSException jmse) {
            String errMsg = "An error occurred getting a request service to use " + "in this transaction. The exception is: "
                    + jmse.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ScheduledCommandException(errMsg, jmse);
        }

        // Query for the Service object.
        List<com.amazon.aws.moa.jmsobjects.services.v1_0.Service> results = null;
        try {
            long startTime = System.currentTimeMillis();
            results = service.query(querySpec, rs);
            long time = System.currentTimeMillis() - startTime;
            logger.info(LOGTAG + "Queried the AWS Account Service for " + "Service objects in " + time + " ms. Found " + results.size()
                    + " services.");
        } catch (EnterpriseObjectQueryException eoqe) {
            String errMsg = "An error occurred querying for the " + "Service object The exception is: " + eoqe.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ScheduledCommandException(errMsg, eoqe);
        }

        // In any case, release the producer back to the pool.
        finally {
            getAwsAccountServiceProducerPool().releaseProducer((PointToPointProducer) rs);
        }

        com.amazon.aws.moa.jmsobjects.services.v1_0.Service resultService = null;

        return results;
    }

    private void createService(com.amazon.aws.moa.jmsobjects.services.v1_0.Service service) throws ScheduledCommandException {

        String LOGTAG = "[AwsServiceDetectionScheduledCommand.createService] ";

        if (service == null) {
            String errMsg = "No Service provided. Can't continue.";
            logger.error(errMsg);
            throw new ScheduledCommandException(errMsg);
        }

        // Get a RequestService to use for this transaction.
        RequestService rs = null;
        try {
            rs = (RequestService) getAwsAccountServiceProducerPool().getExclusiveProducer();
        } catch (JMSException jmse) {
            String errMsg = "An error occurred getting a request service to use " + "in this transaction. The exception is: "
                    + jmse.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ScheduledCommandException(errMsg, jmse);
        }

        // Create the Service object.
        Result result = null;
        try {
            long startTime = System.currentTimeMillis();
            result = (Result) service.create(rs);
            long time = System.currentTimeMillis() - startTime;
            logger.info(LOGTAG + "Created Service object in " + time + " ms. " + "Result status is: " + result.getStatus());
        } catch (EnterpriseObjectCreateException eoce) {
            String errMsg = "An error occurred querying for the " + "Service object The exception is: " + eoce.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ScheduledCommandException(errMsg, eoce);
        }

        // In any case, release the producer back to the pool.
        finally {
            getAwsAccountServiceProducerPool().releaseProducer((PointToPointProducer) rs);
        }

        return;
    }

    private boolean isServiceUpdateRequired() {

        return false;
    }

    private com.amazon.aws.moa.jmsobjects.services.v1_0.Service buildAeoServiceFromAwsService(
            com.amazonaws.services.support.model.Service awsService) throws ScheduledCommandException {

        // Get a configured MOA Service object from AppCondfig
        com.amazon.aws.moa.jmsobjects.services.v1_0.Service aeoService = new com.amazon.aws.moa.jmsobjects.services.v1_0.Service();
        try {
            aeoService = (com.amazon.aws.moa.jmsobjects.services.v1_0.Service) getAppConfig()
                    .getObjectByType(aeoService.getClass().getName());
        } catch (EnterpriseConfigurationObjectException ecoe) {
            String errMsg = "An error occurred getting an object from " + "AppConfig. The exception is: " + ecoe.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ScheduledCommandException(errMsg, ecoe);
        }

        // Set the simple fields of the aeoService
        try {
            aeoService.setAwsServiceName(awsService.getName());
            aeoService.setAwsServiceCode(awsService.getCode());
            aeoService.setAwsStatus(ACTIVE_AWS_SERVICE_STATUS);
            aeoService.setSiteStatus(BLOCKED_PENDING_REVIEW_SITE_SERVICE_STATUS);
            aeoService.setAwsServiceLandingPageUrl(DEFAULT_SERVICE_URL);
            aeoService.setAwsHipaaEligible(DEFAULT_AWS_HIPAA_ELIGIBLE);
            aeoService.setSiteHipaaEligible(DEFAULT_SITE_HIPAA_ELIGIBLE);
            aeoService.setCreateUser(DEFAULT_CREATE_USER);
            Datetime createDatetime = new Datetime("Create", System.currentTimeMillis());
            aeoService.setCreateDatetime(createDatetime);
        } catch (EnterpriseFieldException efe) {
            String errMsg = "An error occurred setting the field values of " + "Service. The exception is: " + efe.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ScheduledCommandException(errMsg, efe);
        }

        // Set the category values
        List<Category> categoryList = awsService.getCategories();
        ListIterator<Category> li = categoryList.listIterator();
        while (li.hasNext()) {
            Category cat = li.next();
            aeoService.addCategory(cat.getName());
        }

        return aeoService;

    }

    private boolean isInAwsServiceList(String serviceCode, List<Service> serviceList) {

        ListIterator li = serviceList.listIterator();
        while (li.hasNext()) {
            Service service = (Service) li.next();
            if (service.getCode().equalsIgnoreCase(serviceCode))
                return true;
        }

        return false;
    }
}
