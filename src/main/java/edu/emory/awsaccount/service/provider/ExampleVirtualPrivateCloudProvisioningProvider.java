/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2017 Emory University. All rights reserved.
 ******************************************************************************/

package edu.emory.awsaccount.service.provider;

// Java utilities

import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.VirtualPrivateCloudProvisioning;
import com.amazon.aws.moa.jmsobjects.user.v1_0.UserNotification;
import com.amazon.aws.moa.objects.resources.v1_0.Datetime;
import com.amazon.aws.moa.objects.resources.v1_0.ProvisioningStep;
import com.amazon.aws.moa.objects.resources.v1_0.VirtualPrivateCloudProvisioningQuerySpecification;
import com.amazon.aws.moa.objects.resources.v1_0.VirtualPrivateCloudRequisition;
import com.service_now.moa.jmsobjects.servicedesk.v2_0.Incident;
import com.service_now.moa.objects.resources.v2_0.IncidentRequisition;
import org.apache.log4j.Category;
import org.jdom.Document;
import org.jdom.Element;
import org.openeai.OpenEaiObject;
import org.openeai.config.AppConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.EnterpriseFieldException;
import org.openeai.config.PropertyConfig;
import org.openeai.layouts.EnterpriseLayoutException;
import org.openeai.moa.XmlEnterpriseObjectException;
import org.openeai.threadpool.ThreadPool;
import org.openeai.threadpool.ThreadPoolException;
import org.openeai.xml.XmlDocumentReader;
import org.openeai.xml.XmlDocumentReaderException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Properties;
import java.util.Random;
import java.util.Set;

/**
 * An example object provider that maintains an in-memory
 * store of VirtualPrivateCloud objects.
 *
 * @author Steve Wheat (swheat@emory.edu)
 */
public class ExampleVirtualPrivateCloudProvisioningProvider extends OpenEaiObject
        implements VirtualPrivateCloudProvisioningProvider {

    private Category logger = OpenEaiObject.logger;
    private AppConfig m_appConfig;
    private int m_provisioningIdNumber = 1;
    private String m_primedDocUri = null;
    private boolean m_verbose = false;
    private HashMap<String, VirtualPrivateCloudProvisioning> m_vpcpMap = new HashMap();
    private ThreadPool m_threadPool = null;
    private int m_threadPoolSleepInterval = 1000;
    private int m_maxWaitTime = 15000;
    private String PENDING_STATUS = "pending";
    private String COMPLETED_STATUS = "completed";
    private String SUCCESS_RESULT = "success";
    private String FAILURE_RESTULT = "failure";
    private String CREATE_UPDATE_USER = "AwsAccountService";
    private String LOGTAG = "[ExampleVirtualPrivateCloudProvisioningProvider] ";

    /**
     * @see VirtualPrivateCloudProvisioningProvider.java
     */
    @Override
    public void init(AppConfig aConfig) throws ProviderException {
        logger.info(LOGTAG + "Initializing...");
        setAppConfig(aConfig);

        // Get the provider properties
        PropertyConfig pConfig = new PropertyConfig();
        try {
            pConfig = (PropertyConfig) aConfig
                    .getObject("VirtualPrivateCloudProvisioningProviderProperties");
        } catch (EnterpriseConfigurationObjectException eoce) {
            String errMsg = "Error retrieving a PropertyConfig object from "
                    + "AppConfig: The exception is: " + eoce.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, eoce);
        }

        Properties props = pConfig.getProperties();
        setProperties(props);
        logger.info(LOGTAG + getProperties().toString());

        // Set the primed doc URI for a template provisioning object.
        String primedDocUri = getProperties().getProperty("primedDocumentUri");
        if (primedDocUri == null || primedDocUri.equals("")) {
            String errMsg = "No primedDocumentUri property specified to " +
                    "build a template VirtualPrivateCloudProvisioning object.";
            throw new ProviderException(errMsg);
        } else {
            logger.info(LOGTAG + "primedDocUri property is: " + primedDocUri);
        }
        setPrimedDocumentUri(primedDocUri);


        // Set the verbose property.
        setVerbose(Boolean.valueOf(getProperties().getProperty("verbose", "false")));
        logger.info(LOGTAG + "Verbose property is: " + getVerbose());

        // Set the maxWaitTime property.
        setMaxWaitTime(Integer.valueOf(getProperties().getProperty("maxWaitTime", "15000")));
        logger.info(LOGTAG + "maxWaitTime property is: " + getMaxWaitTime());


        // Get the ThreadPool pool to use.
        ThreadPool tp = null;
        try {
            tp = (ThreadPool) getAppConfig().getObject("VpcProcessingThreadPool");
            setThreadPool(tp);
        } catch (EnterpriseConfigurationObjectException ecoe) {
            // An error occurred retrieving an object from AppConfig.  Log it and
            // throw an exception.
            String errMsg = "An error occurred retrieving an object from " +
                    "AppConfig. The exception is: " + ecoe.getMessage();
            logger.fatal(LOGTAG + errMsg);
            throw new ProviderException(errMsg);
        }

        logger.info(LOGTAG + "Initialization complete.");
    }

    /**
     * @see VirtualPrivateCloudProvisioningProvider.java
     * <p>
     * Note: this implementation queries by ProvisioningId.
     */
    public List<VirtualPrivateCloudProvisioning> query(VirtualPrivateCloudProvisioningQuerySpecification querySpec)
            throws ProviderException {

        // If the ProvisioningId is null, return the whole list.
        if (querySpec.getProvisioningId() == null || querySpec.getProvisioningId().equals("")) {
            Set mapKeys = m_vpcpMap.keySet();
            Iterator it = mapKeys.iterator();
            ArrayList vpcpList = new ArrayList();
            while (it.hasNext()) {
                String key = (String) it.next();
                VirtualPrivateCloudProvisioning vpcp = m_vpcpMap.get(key);
                vpcpList.add(vpcp);
            }
            return vpcpList;
        }

        // If there is no match, return null.
        if (m_vpcpMap.get(querySpec.getProvisioningId()) == null) return null;


            // Otherwise return the VirtualPrivateCloudProvider from the VPCP map
        else {
            List<VirtualPrivateCloudProvisioning> vpcpList = new ArrayList();
            vpcpList.add(m_vpcpMap.get(querySpec.getProvisioningId()));
            return vpcpList;
        }
    }

    /**
     * @see VirtualPrivateCloudProvisioningProvider.java
     */
    public void create(VirtualPrivateCloudProvisioning vpcp) throws ProviderException {
        // If the VPCP is null, throw an exception.
        if (vpcp == null) {
            throw new ProviderException("VirtualPrivateCloudProvisioning is null.");
        }
        // If the VPCP exists, throw an exception.
        if (m_vpcpMap.get(vpcp.getProvisioningId()) != null) {
            throw new ProviderException("Error creating VirtualPrivateCloud" +
                    "Provisioning with VpcId " + vpcp.getProvisioningId() +
                    ". VirtualPrivateCloudProvisioning already exists.");
        } else {
            // Add the VPCP to the map.
            m_vpcpMap.put(vpcp.getProvisioningId(), vpcp);
        }
    }

    /**
     * @see VirtualPrivateCloudProvisioningProvider.java
     */
    public VirtualPrivateCloudProvisioning generate(VirtualPrivateCloudRequisition vpcr)
            throws ProviderException {

        // Read the VirtualPrivateCloudProvisioning object out of a template
        // Create-Request message.
        XmlDocumentReader xmlReader = new XmlDocumentReader();
        Document replyDoc = null;

        try {
            if (getVerbose()) logger.info("Reading primed document for template object...");
            replyDoc = xmlReader.initializeDocument(getPrimedDocumentUri(), false);
            if (getVerbose()) logger.info("Read primed document for template object.");
        } catch (XmlDocumentReaderException xdre) {
            String errMsg = "An error occurred reading the XML document. The " +
                    "exception is: " + xdre.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, xdre);
        }
        Element e = replyDoc.getRootElement().getChild("DataArea")
                .getChild("VirtualPrivateCloudProvisioning");

        // Get a configured VirtualPrivateCloudProvisioning object from AppConfig
        VirtualPrivateCloudProvisioning vpcp =
                new VirtualPrivateCloudProvisioning();
        try {
            vpcp = (VirtualPrivateCloudProvisioning) m_appConfig
                    .getObjectByType(vpcp.getClass().getName());
        } catch (EnterpriseConfigurationObjectException ecoe) {
            String errMsg = "An error occurred retrieving an object from " +
                    "AppConfig. The exception is: " + ecoe.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, ecoe);
        }

        // Build the VirtualPrivateCloudProvisioning object from the XML element.
        try {
            vpcp.buildObjectFromInput(e);
        } catch (EnterpriseLayoutException ele) {
            String errMsg = "An error occurred building the object from " +
                    "the XML element. The exception is: " + ele.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, ele);
        }

        // Set the values of the VPCP.
        try {
            vpcp.setProvisioningId("rhedcloud-vpc-" + m_provisioningIdNumber++);
            vpcp.setVirtualPrivateCloudRequisition(vpcr);
            vpcp.setStatus("pending");
            vpcp.setCreateUser("AwsAccountService");
            vpcp.setCreateDatetime(new Datetime("Create", System.currentTimeMillis()));
        } catch (EnterpriseFieldException efe) {
            String errMsg = "An error occurred setting the values of the " +
                    "VirtualPrivateCloud object. The exception is: " +
                    efe.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, efe);
        }

        // Verify the order of steps
        List steps = vpcp.getProvisioningStep();
        listOrderOfSteps(steps);

        // Add the VPCP to the map.
        m_vpcpMap.put(vpcp.getProvisioningId(), vpcp);

        // Add the VPCP to the ThreadPool for processing.
        // If this thread pool is set to check for available threads before
        // adding jobs to the pool, it may throw an exception indicating it
        // is busy when we try to add a job. We need to catch that exception
        // and try to add the job until we are successful.
        boolean jobAdded = false;
        while (jobAdded == false) {
            try {
                logger.info(LOGTAG + "Adding job to threadpool for " +
                        "ProvisioningId: " + vpcp.getProvisioningId());
                getThreadPool().addJob(new ProcessVpcProvisioning(vpcp.getProvisioningId()));
                jobAdded = true;
            } catch (ThreadPoolException tpe) {
                // The thread pool is busy. Log it and sleep briefly to try to add
                // the job again later.
                String msg = "The thread pool is busy. Sleeping for " +
                        getSleepInterval() + " milliseconds.";
                logger.debug(LOGTAG + msg);
                try {
                    Thread.sleep(getSleepInterval());
                } catch (InterruptedException ie) {
                    // An error occurred while sleeping to allow threads in the pool
                    // to clear for processing. Log it and throw and exception.
                    String errMsg = "An error occurred while sleeping to allow " +
                            "threads in the pool to clear for processing. The exception " +
                            "is " + ie.getMessage();
                    logger.fatal(LOGTAG + errMsg);
                    throw new ProviderException(errMsg);
                }
            }
        }

        // Return the object.
        logger.info(LOGTAG + "Returned VPCP object: " + vpcpToXmlString(vpcp));
        return vpcp;
    }

    /**
     * @see VirtualPrivateCloudProvider.java
     */
    public void update(VirtualPrivateCloudProvisioning vpcp) throws ProviderException {

        // Replace the object in the map with the same ProvisioningId.
        m_vpcpMap.put(vpcp.getProvisioningId(), vpcp);

        return;
    }

    /**
     * @see VirtualPrivateCloudProvider.java
     */
    public void delete(VirtualPrivateCloudProvisioning vpcp) throws ProviderException {

        // Remove the object in the map with the same ProvisinoingId.
        m_vpcpMap.remove(vpcp.getProvisioningId());

        return;
    }

    /**
     * @param String, the URI to a primed document containing a
     *                sample object.
     *                <p>
     *                This method sets the primed document URI property
     */
    private void setPrimedDocumentUri(String primedDocumentUri) {
        m_primedDocUri = primedDocumentUri;
    }

    /**
     * @return String, the URI to a primed document containing a
     * sample object
     * <p>
     * This method returns the value of the primed document URI property
     */
    private String getPrimedDocumentUri() {
        return m_primedDocUri;
    }

    /**
     * @param boolean, the verbose logging property
     *                 <p>
     *                 This method sets the verbose logging property
     */
    private void setVerbose(boolean verbose) {
        m_verbose = verbose;
    }

    /**
     * @return boolean, the verbose logging property
     * <p>
     * This method returns the verbose logging property
     */
    private boolean getVerbose() {
        return m_verbose;
    }

    /**
     * @param int, the max wait time property
     *             <p>
     *             This method sets the max wait time property
     */
    private void setMaxWaitTime(int maxWaitTime) {
        m_maxWaitTime = maxWaitTime;
    }

    /**
     * @return int, the max wait time property
     * <p>
     * This method gets the max wait time property
     */
    private int getMaxWaitTime() {
        return m_maxWaitTime;
    }

    /**
     * This method gets the thread pool.
     */
    public final ThreadPool getThreadPool() {
        return m_threadPool;
    }

    /**
     * This method sets the thread pool.
     */
    private final void setThreadPool(ThreadPool tp) {
        m_threadPool = tp;
    }

    /**
     * This method gets the value of the threadPoolSleepInteval.
     */
    public final int getSleepInterval() {
        return m_threadPoolSleepInterval;
    }

    /**
     * @param AppConfig , the AppConfig object of this provider.
     *                  <p>
     *                  This method sets the AppConfig object for this provider to
     *                  use.
     */
    private void setAppConfig(AppConfig aConfig) {
        m_appConfig = aConfig;
    }

    /**
     * @return AppConfig, the AppConfig of this provider.
     * <p>
     * This method returns a reference to the AppConfig this provider is
     * using.
     */
    private AppConfig getAppConfig() {
        return m_appConfig;
    }

    private void listOrderOfSteps(List steps) {
        // Confirm order of steps
        ListIterator stepIterator = steps.listIterator();
        int stepSeq = 0;
        while (stepIterator.hasNext()) {
            ProvisioningStep step = (ProvisioningStep) stepIterator.next();
            logger.info(LOGTAG + "Step sequence " + ++stepSeq +
                    " has step number " + step.getStepId());
        }
    }

    private String vpcpToXmlString(VirtualPrivateCloudProvisioning vpcp) {
        String sVpcp = null;
        try {
            sVpcp = vpcp.toXmlString();
        } catch (XmlEnterpriseObjectException xeoe) {
            logger.error(xeoe.getMessage());
        }
        return sVpcp;
    }

    /**
     * A transaction to simulate processing of a VPC provisioning request.
     */
    private class ProcessVpcProvisioning implements java.lang.Runnable {

        private String m_provisioningId = null;

        public ProcessVpcProvisioning(String provisioningId) {
            logger.info(LOGTAG + "Initializing provisioning process for " +
                    "ProvisioningId: " + provisioningId);
            m_provisioningId = provisioningId;
        }

        public void run() {
            String LOGTAG = "[ProcessVpcProvisioning{" + m_provisioningId + "}] ";
            logger.info(LOGTAG + "Processing ProvisioningId number: " + m_provisioningId);

            // Retrieve the provisioning object from the map.
            VirtualPrivateCloudProvisioning vpcp = m_vpcpMap.get(m_provisioningId);

            // Set the status to "in progress"
            try {
                vpcp.setStatus(PENDING_STATUS);
            } catch (EnterpriseFieldException efe) {
                String errMsg = "An error occurred setting the values of " +
                        "the VirtualPrivateCloudProvisioning object. The exception"
                        + " is: " + efe.getMessage();
                logger.error(LOGTAG + errMsg);
            }

            List steps = vpcp.getProvisioningStep();
            ListIterator stepIterator = steps.listIterator();

            // Set steps completion status for each step.
            int stepSeq = 1;
            while (stepIterator.hasNext()) {
                ProvisioningStep step = (ProvisioningStep) stepIterator.next();
                if (step.getStatus().equalsIgnoreCase(PENDING_STATUS)) {
                    try {
                        step.setStatus(COMPLETED_STATUS);
                        step.setStepResult(SUCCESS_RESULT);
                        step.setLastUpdateUser(CREATE_UPDATE_USER);
                        vpcp.setLastUpdateUser(CREATE_UPDATE_USER);
                        long time = System.currentTimeMillis();
                        vpcp.setLastUpdateDatetime(new Datetime("LastUpdate", System.currentTimeMillis()));
                        step.setLastUpdateDatetime(new Datetime("LastUpdate", System.currentTimeMillis()));

                    } catch (EnterpriseFieldException efe) {
                        String errMsg = "An error occurred setting the values of " +
                                "the ProvisioningStep object. The exception"
                                + " is: " + efe.getMessage();
                        logger.error(LOGTAG + errMsg);
                    }
                    logger.info(LOGTAG + "Step sequence" + stepSeq + " StepId "
                            + step.getStepId() + " completed.");

                    // Sleep for a while to simulate processing.
                    try {
                        int sleepInterval = getStepSleepInterval();
                        logger.info(LOGTAG + "Waiting for " + sleepInterval +
                                " ms before starting step seq " + ++stepSeq);
                        Thread.sleep(sleepInterval);
                    } catch (InterruptedException ie) {
                        String errMsg = "An error occurred sleeping to " +
                                "simluate processing. The exception is: " +
                                ie.getMessage();
                        logger.error(LOGTAG + errMsg);
                    }
                }
            }
            // Set the VPCP status to completed
            try {
                vpcp.setStatus(COMPLETED_STATUS);
                logger.info(LOGTAG + "All steps are complete. Set VPC " +
                        "Provisioning status to : " + vpcp.getStatus());
            } catch (EnterpriseFieldException efe) {
                String errMsg = "An error occurred setting the values of " +
                        "the VirtualPrivateCloudProvisioning object. The exception"
                        + " is: " + efe.getMessage();
                logger.error(LOGTAG + errMsg);
            }

            List verifySteps = vpcp.getProvisioningStep();
            listOrderOfSteps(verifySteps);
            logger.info(LOGTAG + "Completed state of VPCP: " + vpcpToXmlString(vpcp));
        }

        private int getStepSleepInterval() {
            Random rand = new Random();
            int n = rand.nextInt(m_maxWaitTime) + 1;
            logger.info(LOGTAG + "Returing random sleep interval: " + n + " ms.");
            return n;
        }
    }

    public int notifyCentralAdministrators(UserNotification notification) {
        return 1;
    }

    public List<String> getCentralAdministrators() {
        return new ArrayList<String>();
    }

    public Incident generateIncident(IncidentRequisition req) {
        return new Incident();
    }
}
