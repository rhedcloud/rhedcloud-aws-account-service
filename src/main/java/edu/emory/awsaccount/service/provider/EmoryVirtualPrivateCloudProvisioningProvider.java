/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2017 Emory University. All rights reserved. 
 ******************************************************************************/

package edu.emory.awsaccount.service.provider;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
// Java utilities
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Properties;
import java.util.Random;
import java.util.StringTokenizer;

import javax.jms.JMSException;

import org.apache.commons.io.IOUtils;
// Log4j
import org.apache.log4j.Category;

// JDOM
import org.jdom.Document;
import org.jdom.Element;

// OpenEAI foundation
import org.openeai.OpenEaiObject;
import org.openeai.config.AppConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.EnterpriseFieldException;
import org.openeai.config.PropertyConfig;
import org.openeai.jms.producer.MessageProducer;
import org.openeai.jms.producer.PointToPointProducer;
import org.openeai.jms.producer.ProducerPool;
import org.openeai.layouts.EnterpriseLayoutException;
import org.openeai.moa.EnterpriseObjectCreateException;
import org.openeai.moa.EnterpriseObjectGenerateException;
import org.openeai.moa.EnterpriseObjectQueryException;
import org.openeai.moa.EnterpriseObjectUpdateException;
import org.openeai.moa.XmlEnterpriseObjectException;
import org.openeai.moa.objects.resources.Result;
import org.openeai.moa.objects.resources.v1_0.QueryLanguage;
import org.openeai.threadpool.ThreadPool;
import org.openeai.threadpool.ThreadPoolException;
import org.openeai.transport.RequestService;
import org.openeai.utils.filetransfer.handlers.TransferHandlerException;
import org.openeai.utils.lock.Key;
import org.openeai.utils.lock.Lock;
import org.openeai.utils.lock.LockAlreadySetException;
import org.openeai.utils.lock.LockException;
import org.openeai.utils.sequence.Sequence;
import org.openeai.utils.sequence.SequenceException;
import org.openeai.xml.XmlDocumentReader;
import org.openeai.xml.XmlDocumentReaderException;

import com.amazon.aws.moa.jmsobjects.cloudformation.v1_0.Stack;
import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.Account;

//AWS Message Object API (MOA)

import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.VirtualPrivateCloudProvisioning;
import com.amazon.aws.moa.jmsobjects.user.v1_0.AccountUser;
import com.amazon.aws.moa.jmsobjects.user.v1_0.UserNotification;
import com.amazon.aws.moa.objects.resources.v1_0.AccountQuerySpecification;
import com.amazon.aws.moa.objects.resources.v1_0.Datetime;
import com.amazon.aws.moa.objects.resources.v1_0.Output;
import com.amazon.aws.moa.objects.resources.v1_0.Property;
import com.amazon.aws.moa.objects.resources.v1_0.ProvisioningStep;
import com.amazon.aws.moa.objects.resources.v1_0.StackRequisition;
import com.amazon.aws.moa.objects.resources.v1_0.VirtualPrivateCloudProvisioningQuerySpecification;
import com.amazon.aws.moa.objects.resources.v1_0.VirtualPrivateCloudRequisition;
import com.service_now.moa.jmsobjects.servicedesk.v2_0.Incident;
import com.service_now.moa.objects.resources.v2_0.IncidentRequisition;

import edu.emory.awsaccount.service.provider.step.Step;
import edu.emory.awsaccount.service.provider.step.StepException;
import edu.emory.moa.jmsobjects.identity.v1_0.RoleAssignment;
import edu.emory.moa.jmsobjects.identity.v2_0.Person;
import edu.emory.moa.jmsobjects.network.v1_0.Cidr;
import edu.emory.moa.jmsobjects.network.v1_0.CidrAssignment;
import edu.emory.moa.jmsobjects.validation.v1_0.EmailAddressValidation;
import edu.emory.moa.objects.resources.v1_0.CidrRequisition;
import edu.emory.moa.objects.resources.v1_0.EmailAddressValidationQuerySpecification;
import edu.emory.moa.objects.resources.v1_0.RoleAssignmentQuerySpecification;
import edu.emory.moa.objects.resources.v2_0.PersonQuerySpecification;

/**
 *  A provider that maintains provisions AWS accounts and VPC
 *  in Emory infrastructure and AWS.
 *
 * @author Steve Wheat (swheat@emory.edu)
 *
 */
public class  EmoryVirtualPrivateCloudProvisioningProvider extends OpenEaiObject 
implements VirtualPrivateCloudProvisioningProvider {

	private Category logger = OpenEaiObject.logger;
	private AppConfig m_appConfig;
	private String m_primedDocUrl = null;
	private boolean m_verbose = false;
	private Sequence m_provisioningIdSequence = null;
	private Sequence m_accountSequence = null;
	private String m_centralAdminRoleDn = null;
	private ProducerPool m_awsAccountServiceProducerPool = null;
	private ProducerPool m_idmServiceProducerPool = null;
	private ProducerPool m_serviceNowServiceProducerPool = null;
	private ThreadPool m_threadPool = null;
	private int m_threadPoolSleepInterval = 1000;
	private String LOGTAG = "[EmoryVirtualPrivateCloudProvisioningProvider] ";
	protected String COMPLETED_STATUS = "completed";
	protected String PENDING_STATUS = "pending";
	protected String ROLLBACK_STATUS = "rolled back";
	protected String SUCCESS_RESULT = "success";
	protected String FAILURE_RESULT = "failure";
	
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
			pConfig = (PropertyConfig)aConfig
				.getObject("VirtualPrivateCloudProvisioningProviderProperties");
		} 
		catch (EnterpriseConfigurationObjectException eoce) {
			String errMsg = "Error retrieving a PropertyConfig object from "
					+ "AppConfig: The exception is: " + eoce.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg, eoce);
		}
		
		Properties props = pConfig.getProperties();
		setProperties(props);
		logger.info(LOGTAG + getProperties().toString());
		
		// Set the verbose property.
		setVerbose(Boolean.valueOf(getProperties().getProperty("verbose", "false")));
		logger.info(LOGTAG + "Verbose property is: " + getVerbose());
		
		// Set the verbose property.
		setCentralAdminRoleDn(getProperties().getProperty("centralAdminRoleDn", null));
		logger.info(LOGTAG + "centralAdminRoleDn is: " + getCentralAdminRoleDn());
		
		// Set the primed doc URL for a template provisioning object.
		String primedDocUrl = getProperties().getProperty("primedDocumentUri");
		setPrimedDocumentUrl(primedDocUrl);
		logger.info(LOGTAG + "primedDocumentUrl property is: " + primedDocUrl);
		
		// Get the sequences to use.
		// This provider needs a sequence to generate a unique ProvisioningId
		// for each transaction in multiple threads and multiple instances.
		Sequence seq = null;
		try {
			seq = (Sequence)getAppConfig().getObject("ProvisioningIdSequence");
			setProvisioningIdSequence(seq);
		}
		catch (EnterpriseConfigurationObjectException ecoe) {
			// An error occurred retrieving an object from AppConfig. Log it and
			// throw an exception.
			String errMsg = "An error occurred retrieving an object from " +
					"AppConfig. The exception is: " + ecoe.getMessage();
			logger.fatal(LOGTAG + errMsg);
			throw new ProviderException(errMsg);
		}
		
		// This provider needs a sequence to generate a unique account name.
		Sequence accountSeq = null;
		try {
			accountSeq = (Sequence)getAppConfig().getObject("AccountSequence");
			setAccountSequence(accountSeq);
		}
		catch (EnterpriseConfigurationObjectException ecoe) {
			// An error occurred retrieving an object from AppConfig. Log it and
			// throw an exception.
			String errMsg = "An error occurred retrieving an object from " +
					"AppConfig. The exception is: " + ecoe.getMessage();
			logger.fatal(LOGTAG + errMsg);
			throw new ProviderException(errMsg);
		}
		
		// This provider needs to send messages to the AWS account service
		// to initialize provisioning transactions.
		ProducerPool p2p1 = null;
		try {
			p2p1 = (ProducerPool)getAppConfig()
				.getObject("AwsAccountServiceProducerPool");
			setAwsAccountServiceProducerPool(p2p1);
		}
		catch (EnterpriseConfigurationObjectException ecoe) {
			// An error occurred retrieving an object from AppConfig. Log it and
			// throw an exception.
			String errMsg = "An error occurred retrieving an object from " +
					"AppConfig. The exception is: " + ecoe.getMessage();
			logger.fatal(LOGTAG + errMsg);
			throw new ProviderException(errMsg);
		}
		
		// This provider needs to send messages to the ServiceNow service
		// to initialize provisioning transactions.
		ProducerPool p2p2 = null;
		try {
			p2p2 = (ProducerPool)getAppConfig()
				.getObject("ServiceNowServiceProducerPool");
			setServiceNowServiceProducerPool(p2p2);
		}
		catch (EnterpriseConfigurationObjectException ecoe) {
			// An error occurred retrieving an object from AppConfig. Log it and
			// throw an exception.
			String errMsg = "An error occurred retrieving an object from " +
					"AppConfig. The exception is: " + ecoe.getMessage();
			logger.fatal(LOGTAG + errMsg);
			throw new ProviderException(errMsg);
		}
				
		// Get the ThreadPool pool to use. 
		// This provider needs a thread pool in which to process concurrent
		// provisioning transactions.
		ThreadPool tp = null;
		try {
			tp = (ThreadPool)getAppConfig().getObject("VpcProcessingThreadPool");
			setThreadPool(tp);
		}
		catch (EnterpriseConfigurationObjectException ecoe) {
			// An error occurred retrieving an object from AppConfig. Log it and
			// throw an exception.
			String errMsg = "An error occurred retrieving an object from " +
					"AppConfig. The exception is: " + ecoe.getMessage();
			logger.fatal(LOGTAG + errMsg);
			throw new ProviderException(errMsg);
		}
		
		// Initialize all provisioning steps this provider will use to 
		// verify the runtime configuration as best we can.
		// Get a list of all step properties.
		List<PropertyConfig> stepPropConfigs = null;
		try {
			stepPropConfigs = getAppConfig().getObjectsLike("ProvisioningStep");
		}
		catch (EnterpriseConfigurationObjectException eoce) {
			String errMsg = "An error occurred getting ProvisioningStep " +
				"properties from AppConfig. The exception is: " +
				eoce.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg, eoce);
		}
		logger.info(LOGTAG + "There are " + stepPropConfigs.size() + " steps.");
		
		// Convert property configs to properties
		List<Properties> stepProps = new ArrayList<Properties>();
		ListIterator stepPropConfigsIterator = stepPropConfigs.listIterator();
		while (stepPropConfigsIterator.hasNext()) {
			PropertyConfig stepConfig = (PropertyConfig)stepPropConfigsIterator.next();
			Properties stepProp = stepConfig.getProperties();
			stepProps.add(stepProp);
		}
		
		// Sort the list by stepId integer.
		stepProps.sort(new StepPropIdComparator(1));
		
		// For each property instantiate the step and log out its details.
		List<Step> completedSteps = new ArrayList();
		ListIterator stepPropsIterator = stepProps.listIterator();
		int i = 0;
		while (stepPropsIterator.hasNext()) {
			i++;
			Properties sp = (Properties)stepPropsIterator.next();
			String className = sp.getProperty("className");
			String stepId = sp.getProperty("stepId");
			String stepType = sp.getProperty("type");
			if (className != null) {
				// Instantiate the step
				Step step = null;
				try {
					logger.info(LOGTAG + "Step " + stepId + ": " + stepType);
					Class stepClass = Class.forName(className);
					step = (Step)stepClass.newInstance();
					logger.info(LOGTAG + "Verified class for step " 
						+ stepId +": " + className);
				}
				catch (ClassNotFoundException cnfe) {
					String errMsg = "An error occurred instantiating " +
						"a step. The exception is: " + cnfe.getMessage();
					logger.error(LOGTAG + errMsg);
					throw new ProviderException(errMsg, cnfe);
				}
				catch (IllegalAccessException iae) {
					String errMsg = "An error occurred instantiating " +
						"a step. The exception is: " + iae.getMessage();
					logger.error(LOGTAG + errMsg);
					throw new ProviderException(errMsg, iae);
				}
				catch (InstantiationException ie) {
					String errMsg = "An error occurred instantiating " +
						"a step. The exception is: " + ie.getMessage();
					logger.error(LOGTAG + errMsg);
					throw new ProviderException(errMsg, ie);
				}
			}	
		}
		
		logger.info(LOGTAG + "Initialization complete.");
	}

	/**
	 * @see VirtualPrivateCloudProvisioningProvider.java
	 * 
	 * This method proxys a query to an RDBMS command that handles it. The 
	 * purpose of including this operation in this command (and not just the
	 * generate) operations is that it will give us one command that should
	 * handle all broad access to the VirtualPrivateCloudProvisioining service
	 * operations. In general, applications and clients will only need to 
	 * perform the query and generate operations and the create, update, and
	 * delete operations will be handled by and RDBMS connector deployment
	 * and accessed by this command and administrative applications like the
	 * VPCP web application.
	 */
	public List<VirtualPrivateCloudProvisioning> 
		query(VirtualPrivateCloudProvisioningQuerySpecification querySpec)
			throws ProviderException {
			logger.info(LOGTAG + "Querying for VPCP with ProvisioningId: " + 
					querySpec.getProvisioningId());
		
			// Get a configured VirtualPrivateCloudProvisioning object to use.
			VirtualPrivateCloudProvisioning vpcp = new VirtualPrivateCloudProvisioning();
			try {
				vpcp = (VirtualPrivateCloudProvisioning)getAppConfig()
					.getObjectByType(vpcp.getClass().getName());
			}
			catch (EnterpriseConfigurationObjectException ecoe) {
				String errMsg = "An error occurred getting an object from " +
					"AppConfig. The exception is: " + ecoe.getMessage();
				logger.error(LOGTAG + errMsg);
				throw new ProviderException();
			}

			// Get a RequestService to use for this transaction.
			RequestService rs = null;
			try {
				rs = (RequestService)getAwsAccountServiceProducerPool().getExclusiveProducer();
				PointToPointProducer p2p = (PointToPointProducer)rs;
				p2p.setRequestTimeoutInterval(1000000);
			}
			catch (JMSException jmse) {
				String errMsg = "An error occurred getting a request service to use " +
					"in this transaction. The exception is: " + jmse.getMessage();
				logger.error(LOGTAG + errMsg);
				throw new ProviderException(errMsg, jmse);
			}
			// Create the VirtualPrivateCloudProvisioningObject.
			List results = null;
			try {
				logger.info(LOGTAG + "Querying for the VPCP...");
				long startTime = System.currentTimeMillis();
				results = vpcp.query(querySpec, rs);
				long time = System.currentTimeMillis() - startTime;
				logger.info(LOGTAG + "Queried for VirtualPrivateCloudProvisioning " +
					"objects in " + time + " ms.");
			}
			catch (EnterpriseObjectQueryException eoce) {
				String errMsg = "An error occurred querying the VirtualPrivate" +
						"CloudProvisioning object The exception is: " + 
						eoce.getMessage();
					logger.error(LOGTAG + errMsg);
					throw new ProviderException(errMsg, eoce);
			}
			// In any case, release the producer back to the pool.
			finally {
				getAwsAccountServiceProducerPool().releaseProducer((PointToPointProducer)rs);
			}
			
			return results;
	}
	
	/**
	 * @see VirtualPrivateCloudProvisioningProvider.java
	 */
	public void create(VirtualPrivateCloudProvisioning vpcp) throws ProviderException {
		String LOGTAG = "[EmoryVirtualPrivateCloudProvisioningProvider.create] ";
		
		// Get a RequestService to use for this transaction.
		RequestService rs = null;
		try {
			rs = (RequestService)getAwsAccountServiceProducerPool().getExclusiveProducer();
		}
		catch (JMSException jmse) {
			String errMsg = "An error occurred getting a request service to use " +
				"in this transaction. The exception is: " + jmse.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg, jmse);
		}
		// Create the VirtualPrivateCloudProvisioningObject.
		try {
			long startTime = System.currentTimeMillis();
			vpcp.create(rs);
			long time = System.currentTimeMillis() - startTime;
			logger.info(LOGTAG + "Created VirtualPrivateCloudProvisioning " +
				"object in " + time + " ms.");
		}
		catch (EnterpriseObjectCreateException eoce) {
			String errMsg = "An error occurred creating the VirtualPrivate" +
					"CloudProvisioning object The exception is: " + 
					eoce.getMessage();
				logger.error(LOGTAG + errMsg);
				throw new ProviderException(errMsg, eoce);
		}
		// In any case, release the producer back to the pool.
		finally {
			getAwsAccountServiceProducerPool().releaseProducer((PointToPointProducer)rs);
		}
	}

	/**
	 * @see VirtualPrivateCloudProvisioningProvider.java
	 */
	public VirtualPrivateCloudProvisioning generate(VirtualPrivateCloudRequisition vpcr)
			throws ProviderException {
     
	    // Get a configured VirtualPrivateCloudProvisioning object from AppConfig
	    VirtualPrivateCloudProvisioning vpcp = 
	    	new VirtualPrivateCloudProvisioning();
	    try {
	    	vpcp = (VirtualPrivateCloudProvisioning)m_appConfig
	    		.getObjectByType(vpcp.getClass().getName());
	    }
	    catch (EnterpriseConfigurationObjectException ecoe) {
	    	String errMsg = "An error occurred retrieving an object from " +
	    	  "AppConfig. The exception is: " + ecoe.getMessage();
	    	logger.error(LOGTAG + errMsg);
	    	throw new ProviderException(errMsg, ecoe);
	    }
	    
	    // Get the next sequence number to identify the VPCP.
	    String seq = null;
	    try {
	    	seq = getProvisioningIdSequence().next();
	    	logger.info(LOGTAG + "The ProvisioningIdSequence value is: " + seq);
	    }
	    catch (SequenceException se) {
	    	String errMsg = "An error occurred getting the next value " +
  	    	  "from the ProvisioningId sequence. The exception is: " + 
  	    	  se.getMessage();
  	    	logger.error(LOGTAG + errMsg);
  	    	throw new ProviderException(errMsg, se);
	    }

		// Set the values of the VPCP.	
		try {
			vpcp.setProvisioningId("emory-vpcp-" + seq);
			logger.info(LOGTAG + "The ProvisioningId is: " + vpcp.getProvisioningId());
			vpcp.setVirtualPrivateCloudRequisition(vpcr);
			vpcp.setStatus(PENDING_STATUS);
			vpcp.setCreateUser("AwsAccountService");
			vpcp.setCreateDatetime(new Datetime("Create", System.currentTimeMillis()));
		}
		catch (EnterpriseFieldException efe) {
			String errMsg = "An error occurred setting the values of the " +
				"VirtualPrivateCloud object. The exception is: " + 
				efe.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg, efe);
		}
		
		// Add all of the steps.
		// Initialize all provisioning steps this provider will use to 
		// verify the runtime configuration as best we can.
		// Get a list of all step properties.
		List<PropertyConfig> stepPropConfigs = null;
		try {
			stepPropConfigs = getAppConfig().getObjectsLike("ProvisioningStep");
		}
		catch (EnterpriseConfigurationObjectException eoce) {
			String errMsg = "An error occurred getting ProvisioningStep " +
				"properties from AppConfig. The exception is: " +
				eoce.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg, eoce);
		}
		logger.info(LOGTAG + "There are " + stepPropConfigs.size() + " steps.");
		
		// Convert property configs to properties
		List<Properties> stepProps = new ArrayList<Properties>();
		ListIterator stepPropConfigsIterator = stepPropConfigs.listIterator();
		while (stepPropConfigsIterator.hasNext()) {
			PropertyConfig stepConfig = (PropertyConfig)stepPropConfigsIterator.next();
			Properties stepProp = stepConfig.getProperties();
			stepProps.add(stepProp);
		}
		
		// Sort the list by stepId integer.
		stepProps.sort(new StepPropIdComparator(1));
		
		// For each property instantiate a provisioning step
		// and add it to the provisioning object.
		ListIterator stepPropsIterator = stepProps.listIterator();
		int i = 0;
		int totalAnticipatedTime = 0;
		while (stepPropsIterator.hasNext()) {
			i++;
			Properties sp = (Properties)stepPropsIterator.next();
			String stepId = sp.getProperty("stepId");
			String stepType = sp.getProperty("type");
			String stepDesc = sp.getProperty("description");
			String stepAnticipatedTime = sp.getProperty("anticipatedTime");
			int anticipatedTime = Integer.parseInt(stepAnticipatedTime);
			totalAnticipatedTime = totalAnticipatedTime + anticipatedTime;
			
			ProvisioningStep pStep = vpcp.newProvisioningStep();
			try {
				pStep.setProvisioningId(vpcp.getProvisioningId());
				pStep.setStepId(stepId);
				pStep.setType(stepType);
				pStep.setDescription(stepDesc);
				pStep.setStatus(PENDING_STATUS);
				pStep.setAnticipatedTime(stepAnticipatedTime);
				pStep.setCreateUser("AwsAccountService");
				Datetime createDatetime = pStep.newCreateDatetime();
				pStep.setCreateDatetime(new Datetime("Create", System.currentTimeMillis()));
				
				vpcp.addProvisioningStep(pStep);
			}
			catch (EnterpriseFieldException efe) {
				String errMsg = "An error occurred setting field values of " +
					"the provisioning object. The exception is: " +
					efe.getMessage();
				logger.error(LOGTAG + errMsg);
				throw new ProviderException(errMsg, efe);
				
			}
			logger.info(LOGTAG + "Added step " + i + "to the provisioning object.");
			logger.info(LOGTAG + "Total anticipated time of this provisioning " +
				"process is " + totalAnticipatedTime + " ms.");
		}
		
		// update the VPCP anticipated time.
		try {
			vpcp.setAnticipatedTime(Integer.toString(totalAnticipatedTime));
		}
		catch (EnterpriseFieldException efe) {
			String errMsg = "An error occurred setting field values of " +
				"the provisioning object. The exception is: " +
				efe.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg, efe);
		}
		
		// Create the VPCP.
		try {
			long createStartTime = System.currentTimeMillis();
			create(vpcp);
			long createTime = System.currentTimeMillis() - createStartTime;
			logger.info(LOGTAG + "Created VPCP in " + createTime + " ms.");
		}
		catch (ProviderException pe) {
			String errMsg = "An error occurred performing the VPCP create. " +
				"The exception is: " + pe.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg, pe);
		} 
		
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
				getThreadPool().addJob(new VirtualPrivateCloudProvisioningTransaction(vpcp));
				jobAdded = true;
			}
			catch (ThreadPoolException tpe) {
				// The thread pool is busy. Log it and sleep briefly to try to
				// add the job again later.
				String msg = "The thread pool is busy. Sleeping for " + 
						getSleepInterval() + " milliseconds.";
				logger.debug(LOGTAG + msg);
				try { Thread.sleep(getSleepInterval()); }
				catch (InterruptedException ie) {
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
		return vpcp;
	}

	/**
	 * @see VirtualPrivateCloudProvider.java
	 */
	public void update(VirtualPrivateCloudProvisioning vpcp) throws ProviderException {		
		String LOGTAG = "[EmoryVirtualPrivateCloudProvisioningProvider.update] ";
		
		// Get a RequestService to use for this transaction.
		RequestService rs = null;
		try {
			rs = (RequestService)getAwsAccountServiceProducerPool().getExclusiveProducer();
		}
		catch (JMSException jmse) {
			String errMsg = "An error occurred getting a request service to use " +
				"in this transaction. The exception is: " + jmse.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg, jmse);
		}
		// Create the VirtualPrivateCloudProvisioningObject.
		try {
			long startTime = System.currentTimeMillis();
			vpcp.update(rs);
			long time = System.currentTimeMillis() - startTime;
			logger.info(LOGTAG + "Updated VirtualPrivateCloudProvisioning " +
				"object in " + time + " ms.");
		}
		catch (EnterpriseObjectUpdateException eoce) {
			String errMsg = "An error occurred updating the VirtualPrivate" +
					"CloudProvisioning object The exception is: " + 
					eoce.getMessage();
				logger.error(LOGTAG + errMsg);
				throw new ProviderException(errMsg, eoce);
		}
		// In any case, release the producer back to the pool.
		finally {
			getAwsAccountServiceProducerPool().releaseProducer((PointToPointProducer)rs);
		}
	}
	
	/**
	 * @see VirtualPrivateCloudProvider.java
	 */
	public void delete(VirtualPrivateCloudProvisioning vpcp) throws ProviderException {		
		String LOGTAG = "[EmoryVirtualPrivateCloudProvisioningProvider.delete] ";
		
		// Get a RequestService to use for this transaction.
		RequestService rs = null;
		try {
			rs = (RequestService)getAwsAccountServiceProducerPool().getExclusiveProducer();
		}
		catch (JMSException jmse) {
			String errMsg = "An error occurred getting a request service to use " +
				"in this transaction. The exception is: " + jmse.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg, jmse);
		}
		// Create the VirtualPrivateCloudProvisioningObject.
		try {
			long startTime = System.currentTimeMillis();
			vpcp.create(rs);
			long time = System.currentTimeMillis() - startTime;
			logger.info(LOGTAG + "Updated VirtualPrivateCloudProvisioning " +
				"object in " + time + " ms.");
		}
		catch (EnterpriseObjectCreateException eoce) {
			String errMsg = "An error occurred updating the VirtualPrivate" +
					"CloudProvisioning object The exception is: " + 
					eoce.getMessage();
				logger.error(LOGTAG + errMsg);
				throw new ProviderException(errMsg, eoce);
		}
		// In any case, release the producer back to the pool.
		finally {
			getAwsAccountServiceProducerPool().releaseProducer((PointToPointProducer)rs);
		}
	}
		
	/**
	 * @param String, the centralAdminRoleDn
	 * <P>
	 * This method sets the centralAdminRoleDn
	 */
	private void setCentralAdminRoleDn(String dn) throws ProviderException {
		if (dn == null) {
			String errMsg = "centralAdminRoleDn property is null. " +
				"Can't continue.";
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg);
		}
		
		m_centralAdminRoleDn = dn;
	}

	/**
	 * @return String, the centralAdminRoleDn property
	 * <P>
	 * This method returns the centralAdminRoleDn
	 */
	private String getCentralAdminRoleDn() {
		return m_centralAdminRoleDn;
	}
	
	/**
	 * @param boolean, the verbose logging property
	 * <P>
	 * This method sets the verbose logging property
	 */
	private void setVerbose(boolean verbose) {
		m_verbose = verbose;
	}

	/**
	 * @return boolean, the verbose logging property
	 * <P>
	 * This method returns the verbose logging property
	 */
	private boolean getVerbose() {
		return m_verbose;
	}
	
	/**
     * @param Sequence, the ProvisinoingId sequence.
     *            <P>
     *            This method sets the ProvisioningId sequence.
     */
    private void setProvisioningIdSequence(Sequence seq) {
        m_provisioningIdSequence = seq;
    }

    /**
     * @return Sequence, the ProvisioningId sequence.
     *         <P>
     *         This method returns a reference to the ProvisioningId sequence.
     */
    private Sequence getProvisioningIdSequence() {
        return  m_provisioningIdSequence;
    }
    
	/**
     * @param Sequence, the Account sequence.
     *            <P>
     *            This method sets the Account sequence.
     */
    private void setAccountSequence(Sequence seq) {
        m_accountSequence = seq;
    }

    /**
     * @return Sequence, the Account sequence.
     *         <P>
     *         This method returns a reference to the Account sequence.
     */
    private Sequence getAccountSequence() {
        return  m_accountSequence;
    }
	
    /**
     * @param ProducerPool, the AWS account service producer pool.
     *            <P>
     *            This method sets the producer pool to use to send 
     *            messages to the AWS Account Service.
     */
    private void setAwsAccountServiceProducerPool(ProducerPool pool) {
        m_awsAccountServiceProducerPool = pool;
    }

    /**
     * @return ProducerPool, the AWS account service producer pool.
     *         <P>
     *         This method returns a reference to the producer pool to use to
     *         send messages to the AWS account service.
     */
    private ProducerPool getAwsAccountServiceProducerPool() {
        return m_awsAccountServiceProducerPool;
    }
    
    /**
     * @param ProducerPool, the ServiceNow service producer pool.
     *            <P>
     *            This method sets the producer pool to use to send 
     *            messages to the ServiceNow Service.
     */
    private void setServiceNowServiceProducerPool(ProducerPool pool) {
        m_serviceNowServiceProducerPool = pool;
    }

    /**
     * @return ProducerPool, the ServiceNow service producer pool.
     *         <P>
     *         This method returns a reference to the producer pool to use to
     *         send messages to the ServiceNow service.
     */
    private ProducerPool getServiceNowServiceProducerPool() {
        return m_serviceNowServiceProducerPool;
    }
    
    /**
     * @param ProducerPool, the IDM service producer pool.
     *            <P>
     *            This method sets the producer pool to use to send 
     *            messages to the IDM Service.
     */
    private void setIdmServiceProducerPool(ProducerPool pool) {
        m_idmServiceProducerPool = pool;
    }

    /**
     * @return ProducerPool, the IDM service producer pool.
     *         <P>
     *         This method returns a reference to the producer pool to use to
     *         send messages to the IDM service.
     */
    private ProducerPool getIdmServiceProducerPool() {
        return m_idmServiceProducerPool;
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
     * @param AppConfig
     *            , the AppConfig object of this provider.
     *            <P>
     *            This method sets the AppConfig object for this provider to
     *            use.
     */
    private void setAppConfig(AppConfig aConfig) {
        m_appConfig = aConfig;
    }

    /**
     * @return AppConfig, the AppConfig of this provider.
     *         <P>
     *         This method returns a reference to the AppConfig this provider is
     *         using.
     */
    private AppConfig getAppConfig() {
        return m_appConfig;
    }
    
    private String vpcpToXmlString(VirtualPrivateCloudProvisioning vpcp) {
    	String sVpcp = null;
    	try {
    		sVpcp = vpcp.toXmlString();
    	}
    	catch (XmlEnterpriseObjectException xeoe) {
    		logger.error(xeoe.getMessage());
    	}
    	return sVpcp;
    }
    
	/**
	 * @param String, the URL to a primed document containing a
	 * sample object.
	 * <P>
	 * This method sets the primed document URL property
	 */
	private void setPrimedDocumentUrl(String primedDocUrl) throws ProviderException {
		if (primedDocUrl == null) {
			String errMsg = "primedDocUrl is null. It is a required property.";
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg);
		}
		
		m_primedDocUrl = primedDocUrl;
	}
	
	/**
	 * @return String, the URL to a primed document containing a
	 * sample object
	 * <P>
	 * This method returns the value of the primed document URL property
	 */
	private String getPrimedDocumentUrl() {
		return m_primedDocUrl;
	}
	
	private VirtualPrivateCloudProvisioningProvider getVirtualPrivateCloudProvisioningProvider() {
		return this;
	}
	
	private class StepPropIdComparator implements Comparator<Properties> {

		int m_order = 1;
		
		public StepPropIdComparator(int order) {
			m_order = order;
		}
		
	    public int compare(Properties prop1, Properties prop2) {
	        int returnVal = 0;
	        
	        // Convert stepIds to integers
	        int stepId1 = Integer.valueOf(prop1.getProperty("stepId"));
	        int stepId2 = Integer.valueOf(prop2.getProperty("stepId"));
	        
	        // Compare integer stepIds.
	        if (stepId1 < stepId2) {
	        	returnVal =  -1;
	        }
	        else if (stepId1 > stepId2) {
	        	returnVal =  1;
	        }
	        else if (stepId1 == stepId2) {
	        	returnVal =  0;
	        }
	        return (returnVal * m_order);
	    }
	}
	
	private class StepIdComparator implements Comparator<Step> {

		int m_order = 1;
		
		public StepIdComparator(int order) {
			m_order = order;
		}
		
	    public int compare(Step step1, Step step2) {
	        int returnVal = 0;
	        
	        // Convert stepIds to integers
	        int stepId1 = Integer.valueOf(step1.getStepId());
	        int stepId2 = Integer.valueOf(step2.getStepId());
	        
	        // Compare integer stepIds.
	        if (stepId1 < stepId2) {
	        	returnVal =  -1;
	        }
	        else if (stepId1 > stepId2) {
	        	returnVal =  1;
	        }
	        else if (stepId1 == stepId2) {
	        	returnVal =  0;
	        }
	        return (returnVal * m_order);
	    }
	}
	
	public Incident generateIncident(IncidentRequisition req) 
		throws ProviderException {
		
		String LOGTAG = "[EmoryVirtualPrivateCloudProvisinoingProvider.generateIncident] ";
		
		if (req == null) {
			String errMsg = "IncidentRequisision is null. " + 
				"Can't generate an Incident.";
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg);
		}

		// Get a configured Incident object from AppConfig.
		Incident incident = new Incident();
	    try {
	    	incident = (Incident)getAppConfig()
		    		.getObjectByType(incident.getClass().getName());
	    }
	    catch (EnterpriseConfigurationObjectException ecoe) {
	    	String errMsg = "An error occurred retrieving an object from " +
	    	  "AppConfig. The exception is: " + ecoe.getMessage();
	    	logger.error(LOGTAG + errMsg);
	    	throw new ProviderException(errMsg, ecoe);
	    }
	    
	    // Log the state of the requisition.
	    try {
	    	logger.info(LOGTAG + "Incident requisition is: " + req.toXmlString());
	    }
	    catch (XmlEnterpriseObjectException xeoe) {
	    	String errMsg = "An error occurred serializing the requisition " +
	  	    	  "to XML. The exception is: " + xeoe.getMessage();
  	    	logger.error(LOGTAG + errMsg);
  	    	throw new ProviderException(errMsg, xeoe);
	    }    
		
		// Get a producer from the pool
		RequestService rs = null;
		try {
			rs = (RequestService)getServiceNowServiceProducerPool()
				.getExclusiveProducer();
		}
		catch (JMSException jmse) {
			String errMsg = "An error occurred getting a producer " +
				"from the pool. The exception is: " + jmse.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg, jmse);
		}
	    
		List results = null;
		try { 
			long generateStartTime = System.currentTimeMillis();
			results = incident.generate(req, rs);
			long generateTime = System.currentTimeMillis() - generateStartTime;
			logger.info(LOGTAG + "Generated Incident in " +
				+ generateTime + " ms. Returned " + results.size() + 
				" result.");
		}
		catch (EnterpriseObjectGenerateException eoge) {
			String errMsg = "An error occurred generating the  " +
	    	  "Incident object. The exception is: " + eoge.getMessage();
	    	logger.error(LOGTAG + errMsg);
	    	throw new ProviderException(errMsg, eoge);
		}
		finally {
			// Release the producer back to the pool
			getServiceNowServiceProducerPool()
				.releaseProducer((MessageProducer)rs);
		}
		
		return (Incident)results.get(0);
	}
	
	public int notifyCentralAdministrators(UserNotification notification)
		throws ProviderException {
		
		// Query for the list of central administrators.
		List<RoleAssignment> roleAssignments = 
			roleAssignmentQuery(getCentralAdminRoleDn());
		
		ListIterator li = roleAssignments.listIterator();
		int i = 0;
		while (li.hasNext()) {
			RoleAssignment ra = (RoleAssignment)li.next();
			String userDn = (String)ra.getExplicitIdentityDNs().getDistinguishedName().get(0);
			String userId = parseUserId(userDn);
			try {
				notification.setUserId(userId);
			}
			catch (EnterpriseFieldException efe) {
				String errMsg = "An error occurred setting the " +
					"field values on an object. The exception is: " +
					efe.getMessage();
				logger.error(LOGTAG + errMsg);
				throw new ProviderException(errMsg, efe);
			}
			createUserNotification(notification);
			i++;
		}
		
		return i;
	}
	
	private String parseUserId(String dn) {
		StringTokenizer st1 = new StringTokenizer(dn, ",");
		String firstToken = st1.nextToken();
		StringTokenizer st2 = new StringTokenizer(firstToken, "=");
		st2.nextToken();
		String userId = st2.nextToken();
		return userId;
	}
	
	private void createUserNotification (UserNotification notification)
		throws ProviderException {
		
		// Create the UserNotification in the AWS Account Service.
		// Get a RequestService to use for this transaction.
		RequestService rs = null;
		try {
			rs = (RequestService)getAwsAccountServiceProducerPool().getExclusiveProducer();
		}
		catch (JMSException jmse) {
			String errMsg = "An error occurred getting a request service to use " +
				"in this transaction. The exception is: " + jmse.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg, jmse);
		}
		// Create the UserNotification object.
		try {
			long startTime = System.currentTimeMillis();
			notification.create(rs);
			long time = System.currentTimeMillis() - startTime;
			logger.info(LOGTAG + "Created UserNotification " +
				"object in " + time + " ms.");
		}
		catch (EnterpriseObjectCreateException eoce) {
			String errMsg = "An error occurred creating the " +
					"UserNotification object The exception is: " + 
					eoce.getMessage();
				logger.error(LOGTAG + errMsg);
				throw new ProviderException(errMsg, eoce);
		}
		// In any case, release the producer back to the pool.
		finally {
			getAwsAccountServiceProducerPool().releaseProducer((PointToPointProducer)rs);
		}
	}
	
	private List<RoleAssignment> roleAssignmentQuery(String roleDn) 
		throws ProviderException {
		
    	// Query the IDM service for all users in the named role
    	// Get a configured AccountUser, RoleAssignment, and 
    	// RoleAssignmentQuerySpecification from AppConfig
		RoleAssignment roleAssignment = new RoleAssignment();
    	RoleAssignmentQuerySpecification querySpec = new RoleAssignmentQuerySpecification();
		try {
			roleAssignment = (RoleAssignment)m_appConfig
				.getObjectByType(roleAssignment.getClass().getName());
			querySpec = (RoleAssignmentQuerySpecification)m_appConfig
				.getObjectByType(querySpec.getClass().getName());
		}
		catch (EnterpriseConfigurationObjectException ecoe) {
			String errMsg = "An error occurred retrieving an object from " +
					"AppConfig. The exception is: " + ecoe.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg, ecoe);
		}
		
		// Set the values of the querySpec.
		try {
			querySpec.setRoleDN(roleDn);
			querySpec.setIdentityType("USER");
			querySpec.setDirectAssignOnly("true");
		}
		catch (EnterpriseFieldException efe) {
			String errMsg = "An error occurred setting the values of the " +
				"query specification object. The exception is: " + 
				efe.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg, efe);
		}
    	
    	// Get a RequestService to use for this transaction.
		RequestService rs = null;
		try {
			rs = (RequestService)getIdmServiceProducerPool().getExclusiveProducer();
		}
		catch (JMSException jmse) {
			String errMsg = "An error occurred getting a request service to use " +
				"in this transaction. The exception is: " + jmse.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg, jmse);
		}
		// Query for the RoleAssignments for the Administrator Role.
		List roleAssignments = null;
		try {
			long startTime = System.currentTimeMillis();
			roleAssignments = roleAssignment.query(querySpec, rs);
			long time = System.currentTimeMillis() - startTime;
			logger.info(LOGTAG + "Queried for RoleAssignments for " +
				"roleDn " + roleDn + " in " + time + " ms. Returned " + 
				roleAssignments.size() + " users in the role.");
		}
		catch (EnterpriseObjectQueryException eoqe) {
			String errMsg = "An error occurred querying for the " +
					"RoleAssignment objects The exception is: " + 
					eoqe.getMessage();
				logger.error(LOGTAG + errMsg);
				throw new ProviderException(errMsg, eoqe);
		}
		// In any case, release the producer back to the pool.
		finally {
			getIdmServiceProducerPool().releaseProducer((PointToPointProducer)rs);
    	}
		
		return roleAssignments;
	}
	
	/**
	 * A transaction to process virtual private cloud provisioning.
	 */
	private class VirtualPrivateCloudProvisioningTransaction implements java.lang.Runnable {

		VirtualPrivateCloudProvisioning m_vpcp = null;
		long m_executionStartTime = 0;

		public VirtualPrivateCloudProvisioningTransaction(VirtualPrivateCloudProvisioning vpcp) {
			logger.info(LOGTAG + "Initializing provisioning process for " +
				"ProvisioningId: " + vpcp.getProvisioningId());
			m_vpcp = vpcp;
		}
		
		public void run() {
			setExecutionStartTime(System.currentTimeMillis());
			String LOGTAG = "[VirtualPrivateCloudProvisioningTransaction{" + 
				getProvisioningId() + "}] ";
			logger.info(LOGTAG +  "Processing ProvisioningId number: " 
				+ getProvisioningId());
			
			// Get a list of all step properties.
			List<PropertyConfig> stepPropConfigs = null;
			try {
				stepPropConfigs = getAppConfig().getObjectsLike("ProvisioningStep");
			}
			catch (EnterpriseConfigurationObjectException eoce) {
				String errMsg = "An error occurred getting ProvisioningStep " +
					"properties from AppConfig. The exception is: " +
					eoce.getMessage();
				logger.error(LOGTAG + errMsg);
				return;
			}
			logger.info(LOGTAG + "There are " + stepPropConfigs.size() + " steps.");
			
			// Convert property configs to properties
			List<Properties> stepProps = new ArrayList<Properties>();
			ListIterator stepPropConfigsIterator = stepPropConfigs.listIterator();
			while (stepPropConfigsIterator.hasNext()) {
				PropertyConfig pConfig = (PropertyConfig)stepPropConfigsIterator.next();
				Properties stepProp = pConfig.getProperties();
				stepProps.add(stepProp);
			}
			
			// Sort the list by stepId integer.
			stepProps.sort(new StepPropIdComparator(1));
			
			// For each property instantiate the step, call the execute
			// method, and if successful, place it in the map of 
			// completed steps.
			List<Step> completedSteps = new ArrayList();
			ListIterator stepPropsIterator = stepProps.listIterator();
			int i = 0;
			while (stepPropsIterator.hasNext()) {
				i++;
				Properties props = (Properties)stepPropsIterator.next();
				String className = props.getProperty("className");
				if (className != null) {
					// Instantiate the step
					Step step = null;
					try {
						Class stepClass = Class.forName(className);
						step = (Step)stepClass.newInstance();
						logger.info(LOGTAG + "Initializing step " + i + ".");
						step.init(getProvisioningId(), props, getAppConfig(), 
							getVirtualPrivateCloudProvisioningProvider());
					}
					catch (ClassNotFoundException cnfe) {
						String errMsg = "An error occurred instantiating " +
							"a step. The exception is: " + cnfe.getMessage();
						logger.error(LOGTAG + errMsg);
						rollbackCompletedSteps(completedSteps);
						return;
					}
					catch (IllegalAccessException iae) {
						String errMsg = "An error occurred instantiating " +
							"a step. The exception is: " + iae.getMessage();
						logger.error(LOGTAG + errMsg);
						rollbackCompletedSteps(completedSteps);
						return;
					}
					catch (InstantiationException ie) {
						String errMsg = "An error occurred instantiating " +
							"a step. The exception is: " + ie.getMessage();
						logger.error(LOGTAG + errMsg);
						rollbackCompletedSteps(completedSteps);
						return;
					}
					catch (StepException se) {
						String errMsg = "An error occurred instantiating " +
							"a step. The exception is: " + se.getMessage();
						logger.error(LOGTAG + errMsg);
						rollbackCompletedSteps(completedSteps);
						return;
					}
					
					// Execute the step
					List<Property> resultProps = null;
					try {
						logger.info(LOGTAG + "Executing Step " + 
								step.getStepId() + ": " + 
								step.getDescription());
						long startTime = System.currentTimeMillis();
						resultProps = step.execute();
						long time = System.currentTimeMillis() - startTime;
						logger.info(LOGTAG + "Completed Step " + 
							step.getStepId() + " with result " + 
							step.getResult() + " in " + time + " ms.");
						logger.info(LOGTAG + "Step result properties are: " + 
							resultPropsToXmlString(resultProps));
						completedSteps.add(step);
						
						// If the result of the step is failure, roll back
						// all completed steps and return.
						if (step.getResult().equals(FAILURE_RESULT)) {
							logger.info(LOGTAG + "Step " + step.getStepId() +
								" failed. Rolling back all completed steps.");
							rollbackCompletedSteps(completedSteps);
							return;
						}
					}
					catch (StepException se) {
						// An error occurred executing the step.
						// Log it and roll back all preceding steps.
						String errMsg = "An error occurred executing Step " + 
							step.getStepId() + "The exception is: " + se.getMessage();
						logger.error(LOGTAG + errMsg);
						try {
							logger.info(LOGTAG + "Setting completed status and failure result...");
							step.update(COMPLETED_STATUS, FAILURE_RESULT, resultProps);
							logger.info(LOGTAG + "Updated to completed status and failure result.");
						}
						catch (StepException se2) {
							String errMsg2 = "An error occurred updating the " +
								"status to indicate failure. The exception " +
								"is: " + se2.getMessage();
							logger.error(LOGTAG + errMsg2);
						}
						rollbackCompletedSteps(completedSteps);
						return;
					}
				}
				else {
					String errMsg = "An error occurred instantiating " +
						"a step. The className property is null.";
					logger.error(LOGTAG + errMsg);
					rollbackCompletedSteps(completedSteps);
					return;
				}
			}
			
			// All steps completed successfully. 
			// Set the end of execution.
			long executionTime = System.currentTimeMillis() - getExecutionStartTime();
			
			// Update the state of the VPCP object in this transaction.
			queryForVpcpBaseline();
			
			// Set the status to complete, the result to success, and the
			// execution time.
			try {
				getVirtualPrivateCloudProvisioning().setStatus(COMPLETED_STATUS);
				getVirtualPrivateCloudProvisioning().setProvisioningResult(SUCCESS_RESULT);
				getVirtualPrivateCloudProvisioning().setActualTime(Long.toString(executionTime));
			}
			catch (EnterpriseFieldException efe) {
				String errMsg = "An error setting field values on the " +
			    	  "VPCP object. The exception is: " + efe.getMessage();
			    logger.error(LOGTAG + errMsg);
			}
			
			// Update the VPCP object.
			try { 
				getVirtualPrivateCloudProvisioningProvider()
					.update(getVirtualPrivateCloudProvisioning());
			}
			catch (ProviderException pe) {
				String errMsg = "An error occurred querying for the  " +
		    	  "current state of a VirtualPrivateCloudProvisioning object. " +
		    	  "The exception is: " + pe.getMessage();
		    	logger.error(LOGTAG + errMsg);
			}
			
			// And we're done.
			return;
			
		}
		
		private void rollbackCompletedSteps(List<Step> completedSteps) {
			logger.info(LOGTAG + "Starting rollback of completed steps...");
			
			// Reverse the order of the completedSteps list.
			completedSteps.sort(new StepIdComparator(-1));
			
			ListIterator completedStepsIterator = completedSteps.listIterator();
			long startTime = System.currentTimeMillis();
			while (completedStepsIterator.hasNext()) {
				Step completedStep = (Step)completedStepsIterator.next();
				try {
					completedStep.rollback();
				}
				catch (StepException se) {
					String errMsg = "An error occurred rolling back step " +
						completedStep.getStepId() + ": " + 
						completedStep.getType() + ". The exception is: " +
						se.getMessage();
					logger.error(LOGTAG + errMsg);
				}
			}
			long time = System.currentTimeMillis() - startTime;
			logger.info(LOGTAG + "Provisioning rollback complete in " + time + " ms.");
			
			// All steps completed successfully. 
			// Set the end of execution.
			long executionTime = System.currentTimeMillis() - getExecutionStartTime();
			
			// Update the state of the VPCP object in this transaction.
			queryForVpcpBaseline();
			
			// Set the status to complete, the result to success, and the
			// execution time.
			try {
				getVirtualPrivateCloudProvisioning().setStatus(COMPLETED_STATUS);
				getVirtualPrivateCloudProvisioning().setProvisioningResult(FAILURE_RESULT);
				getVirtualPrivateCloudProvisioning().setActualTime(Long.toString(executionTime));
			}
			catch (EnterpriseFieldException efe) {
				String errMsg = "An error setting field values on the " +
			    	  "VPCP object. The exception is: " + efe.getMessage();
			    logger.error(LOGTAG + errMsg);
			}
			
			// Update the VPCP object.
			try { 
				getVirtualPrivateCloudProvisioningProvider()
					.update(getVirtualPrivateCloudProvisioning());
			}
			catch (ProviderException pe) {
				String errMsg = "An error occurred querying for the  " +
		    	  "current state of a VirtualPrivateCloudProvisioning object. " +
		    	  "The exception is: " + pe.getMessage();
		    	logger.error(LOGTAG + errMsg);
			}
			
			// The the provider is configured to create an incident
			// in ServiceNow upon failure, create an incident.
			if (false) {
				logger.info(LOGTAG + "Creating an Incident " +
					"in ServiceNow...");
				//TODO: create an incident.
			}
			else {
				logger.info(LOGTAG + "createIncidentOnFailure is " 
					+ "false. Will not create an incident in " 
					+ "ServiceNow.");
			}
		}
		
		private String resultPropsToXmlString(List<Property> resultProps) {
			String stringProps = "";
			
			ListIterator li = resultProps.listIterator();
			while (li.hasNext()) {
				Property prop = (Property)li.next();
				String stringProp = "";
				try {
					stringProp = prop.toXmlString();
					stringProps = stringProps + stringProp;
				}
				catch (XmlEnterpriseObjectException xeoe) {
					String errMsg = "An error occurred serializing a Property "
						+ "object to an XML string.";
					logger.error(LOGTAG + errMsg);
				}
			}
			
			return stringProps;
		}
		
		private String getProvisioningId() {
			return m_vpcp.getProvisioningId();
		}
		
		private void setVirtualPrivateCloudProvisioning(VirtualPrivateCloudProvisioning vpcp) {
			m_vpcp = vpcp;
		}
		
		private VirtualPrivateCloudProvisioning getVirtualPrivateCloudProvisioning() {
			return m_vpcp;
		}
		
		private void queryForVpcpBaseline() {
			// Query for the VPCP object in the AWS Account Service.
			// Get a configured query spec from AppConfig
			VirtualPrivateCloudProvisioningQuerySpecification vpcpqs = new
				VirtualPrivateCloudProvisioningQuerySpecification();
		    try {
		    	vpcpqs = (VirtualPrivateCloudProvisioningQuerySpecification)getAppConfig()
			    		.getObjectByType(vpcpqs.getClass().getName());
		    }
		    catch (EnterpriseConfigurationObjectException ecoe) {
		    	String errMsg = "An error occurred retrieving an object from " +
		    	  "AppConfig. The exception is: " + ecoe.getMessage();
		    	logger.error(LOGTAG + errMsg);
		    }
			
		    // Set the values of the query spec.
		    try {
		    	vpcpqs.setProvisioningId(getProvisioningId());
		    }
		    catch (EnterpriseFieldException efe) {
		    	String errMsg = "An error occurred setting the values of the " +
		  	    	  "VPCP query spec. The exception is: " + efe.getMessage();
		  	    logger.error(LOGTAG + errMsg);
		    }
		    
		    // Log the state of the query spec.
		    try {
		    	logger.info(LOGTAG + "Query spec is: " + vpcpqs.toXmlString());
		    }
		    catch (XmlEnterpriseObjectException xeoe) {
		    	String errMsg = "An error occurred serializing the query spec " +
		  	    	  "to XML. The exception is: " + xeoe.getMessage();
	  	    	logger.error(LOGTAG + errMsg);
		    }
		    
			List results = null;
			try { 
				results = getVirtualPrivateCloudProvisioningProvider()
					.query(vpcpqs);
			}
			catch (ProviderException pe) {
				String errMsg = "An error occurred querying for the  " +
		    	  "current state of a VirtualPrivateCloudProvisioning object. " +
		    	  "The exception is: " + pe.getMessage();
		    	logger.error(LOGTAG + errMsg);
			}
			VirtualPrivateCloudProvisioning vpcp = 
				(VirtualPrivateCloudProvisioning)results.get(0);
			
			setVirtualPrivateCloudProvisioning(vpcp);
		}
		
		private void setExecutionStartTime(long time) {
			m_executionStartTime = time;
		}
		
		private long getExecutionStartTime() {
			return m_executionStartTime;
		}
	}
}		
	
