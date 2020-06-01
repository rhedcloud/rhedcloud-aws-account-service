/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2017 Emory University. All rights reserved. 
 ******************************************************************************/
package edu.emory.awsaccount.service.deprovisioning.step;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import javax.jms.JMSException;

import org.openeai.config.AppConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.EnterpriseFieldException;
import org.openeai.jms.producer.MessageProducer;
import org.openeai.jms.producer.ProducerPool;
import org.openeai.moa.EnterpriseObjectDeleteException;
import org.openeai.moa.EnterpriseObjectQueryException;
import org.openeai.moa.XmlEnterpriseObjectException;
import org.openeai.transport.RequestService;

import com.amazon.aws.moa.objects.resources.v1_0.Property;
import edu.emory.awsaccount.service.provider.AccountDeprovisioningProvider;
import edu.emory.moa.jmsobjects.lightweightdirectoryservices.v1_0.Group;
import edu.emory.moa.objects.resources.v1_0.GroupQuerySpecification;


/**
 * Delete the groups that were created in the provisioning.
 * <P>
 * 
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 21 May 2017
 * @author Tom Cervenka (tcerven@emory.edu)
 * @version 1.0 - 29 May 2020
 * 
 **/
public class DeleteLdsGroup extends AbstractStep implements Step {

	int m_sleepTimeInMillis = 5000;
	private final String LOGTAG="DeleteLdsGroup";

	private ProducerPool m_ldsServiceProducerPool;
	private AppConfig m_aConfig;
	private String m_groupDnTemplate;

	private final String GROUP = "Group.v1_0";
	private final String GROUP_QUERY_SPEC = "GroupQuerySpecification.v1_0";

	public void init (String deprovisioningId, Properties props, 
			AppConfig aConfig, AccountDeprovisioningProvider vpcpp) throws StepException {

		super.init(deprovisioningId, props, aConfig, vpcpp);

		String LOGTAG = getStepTag() + "[DeleteLdsGroup.init] ";

		logger.info(LOGTAG + "Getting custom step properties...");

		String groupDnTemplate = getProperties().getProperty("groupDnTemplate", null);
		setGroupDnTemplate(groupDnTemplate);
		logger.info(LOGTAG + "groupDnTemplate is: " + getGroupDnTemplate());

		m_aConfig = aConfig;

		// Check that required objects are in the appConfig
		try {
			m_aConfig.getObject(GROUP);
			m_aConfig.getObject(GROUP_QUERY_SPEC);
		} catch (EnterpriseConfigurationObjectException ecoe) {
			String errMsg = "An error occurred retrieving the one of the objects from " +
					"AppConfig. The exception is: " + ecoe.getMessage();
			logger.error(LOGTAG + errMsg);
			addResultProperty("errorMessage", errMsg);
			throw new StepException(errMsg);
		}

		// This step needs to send messages to the LDS Service
		// to provision or deprovision the groups for the new account.
		ProducerPool p2p1 = null;
		try {
			p2p1 = (ProducerPool)getAppConfig()
					.getObject("LdsServiceProducerPool");
			setLdsServiceProducerPool(p2p1);
		}
		catch (EnterpriseConfigurationObjectException ecoe) {
			// An error occurred retrieving an object from AppConfig. Log it and
			// throw an exception.
			String errMsg = "An error occurred retrieving an object from " +
					"AppConfig. The exception is: " + ecoe.getMessage();
			logger.error(LOGTAG + errMsg);
			addResultProperty("errorMessage", errMsg);
			throw new StepException(errMsg);
		}

		logger.info(LOGTAG + "Initialization complete.");
	}


	@SuppressWarnings("unchecked")
	private Group queryForGroup(String distinguishedName) 
			throws StepException {
		// Get a producer from the pool
		RequestService rs = null;
		try {
			rs = (RequestService)getLdsServiceProducerPool()
					.getExclusiveProducer();
		}
		catch (JMSException jmse) {
			String errMsg = "An error occurred getting a producer " +
					"from the pool. The exception is: " + jmse.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg, jmse);
		}

		List<Group> results = null;
		try { 
			long queryStartTime = System.currentTimeMillis();

			Group group = (Group) m_aConfig.getObject(GROUP);
			GroupQuerySpecification querySpec = 
					(GroupQuerySpecification) m_aConfig.getObject(GROUP_QUERY_SPEC);
			querySpec.setdistinguishedName(distinguishedName);
			results = group.query(querySpec, rs);
			long queryTime = System.currentTimeMillis() - queryStartTime;
			logger.info(LOGTAG + "Queried for Group in "
					+ queryTime + " ms. There are " + results.size() +
					" result(s)."); 
		}
		catch (EnterpriseObjectQueryException eoqe) {
			String errMsg = "An error occurred querying for the  " +
					"Group object. " +
					"The exception is: " + eoqe.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg, eoqe);
		} catch (EnterpriseConfigurationObjectException e) {
			String errMsg = "An error occurred retrieving a Group or  " +
					"GroupQuerySpec object. " +
					"The exception is: " + e.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg, e);
		} catch (EnterpriseFieldException e) {
			String errMsg = "An error occurred seting the DN in the  " +
					"GroupQuerySpec object. " +
					"The exception is: " + e.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg, e);
		}
		finally {
			// Release the producer back to the pool
			getLdsServiceProducerPool()
			.releaseProducer((MessageProducer)rs);
		}
		if (results.size() == 1) {
			return results.get(0);
		} else {
			if (results.size()==0) {
				return null;
			} else {
				String errMsg = "Expected 1 group from query but got" 
						+ results.size() ;
				logger.error(LOGTAG + errMsg);
				throw new StepException(errMsg);
			}

		}

	}

	private void deleteGroup(Group group) throws StepException {
		// Get a producer from the pool
		RequestService rs = null;
		try {
			rs = (RequestService)getLdsServiceProducerPool()
					.getExclusiveProducer();
		}
		catch (JMSException jmse) {
			String errMsg = "An error occurred getting a producer " +
					"from the pool. The exception is: " + jmse.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg, jmse);
		}

		// Log the state of the Group.
		try {
			logger.info(LOGTAG + "Group is: " +
					group.toXmlString());
		}
		catch (XmlEnterpriseObjectException xeoe) {
			String errMsg = "An error occurred serializing the object " +
					"to XML. The exception is: " + xeoe.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg, xeoe);
		}    

		// Get a producer from the pool
		rs = null;
		try {
			rs = (RequestService)getLdsServiceProducerPool()
					.getExclusiveProducer();
		}
		catch (JMSException jmse) {
			String errMsg = "An error occurred getting a producer " +
					"from the pool. The exception is: " + jmse.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg, jmse);
		}

		try { 
			long deleteStartTime = System.currentTimeMillis();
			group.delete("Delete", rs);
			long deleteTime = System.currentTimeMillis() - deleteStartTime;
			logger.info(LOGTAG + "Deleted Group in "
					+ deleteTime + " ms.");
			//			addResultProperty("deletedGroup", "true"); 
		}
		catch (EnterpriseObjectDeleteException eode) {
			String errMsg = "An error occurred deleting the  " +
					"Group object. The exception is: " + eode.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg, eode);
		}
		finally {
			// Release the producer back to the pool
			getLdsServiceProducerPool()
			.releaseProducer((MessageProducer)rs);
		}
	}

	protected List<Property> run() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + "[DeleteLdsGroup.run] ";
		logger.info(LOGTAG + "Begin deleting group.");

		String accountId = getAccountDeprovisioning().getAccountDeprovisioningRequisition().getAccountId();
		String distinguishedName = buildDnValueFromTemplate(accountId);
		logger.info(LOGTAG + "distinguishedName is "+distinguishedName);
		addResultProperty("distinguishedName", distinguishedName);

		Group group = queryForGroup(distinguishedName);
		if (group != null) {
			deleteGroup(group);
			addResultProperty("deletedGroup", "true");
			logger.info(LOGTAG + "Done deleting group.");
		} else {
			//TODO should this be warn instead of info?
			logger.info(LOGTAG + "Group doesn't exist");
			addResultProperty("deletedGroup", "false");
			addResultProperty("groupExists", "false");
		}

		// Set return properties.
		addResultProperty("stepExecutionMethod", RUN_EXEC_TYPE);
		addResultProperty("sleepTimeInMillis", 
				Integer.toString(getSleepTimeInMillis()));

		// Update the step.
		update(COMPLETED_STATUS, SUCCESS_RESULT);

		// Log completion time.
		long time = System.currentTimeMillis() - startTime;
		logger.info(LOGTAG + "Step run completed in " + time + "ms.");

		// Return the properties.
		return getResultProperties();

	}	


	protected List<Property> simulate() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + 
				"[DeleteLdsGroup.simulate] ";
		logger.info(LOGTAG + "Begin step simulation.");

		// Set return properties.
		addResultProperty("stepExecutionMethod", SIMULATED_EXEC_TYPE);

		// Update the step.
		update(COMPLETED_STATUS, SUCCESS_RESULT);

		// Log completion time.
		long time = System.currentTimeMillis() - startTime;
		logger.info(LOGTAG + "Step simulation completed in " + time + "ms.");

		// Return the properties.
		return getResultProperties();
	}

	protected List<Property> fail() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + 
				"[DeleteLdsGroup.fail] ";
		logger.info(LOGTAG + "Begin step failure simulation.");

		// Set return properties.
		ArrayList<Property> props = new ArrayList<Property>();
		addResultProperty("stepExecutionMethod", FAILURE_EXEC_TYPE);

		// Update the step.
		update(COMPLETED_STATUS, FAILURE_RESULT);

		// Log completion time.
		long time = System.currentTimeMillis() - startTime;
		logger.info(LOGTAG + "Step failure simulation completed in " + time + "ms.");

		// Return the properties.
		return props;
	}

	public void rollback() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + 
				"[DeleteLdsGroup.rollback] ";
		logger.info(LOGTAG + "Rollback called, but this step has nothing to " + 
				"roll back.");
		update(ROLLBACK_STATUS, SUCCESS_RESULT);

		// Log completion time.
		long time = System.currentTimeMillis() - startTime;
		logger.info(LOGTAG + "Rollback completed in " + time + "ms.");
	}

	//	private void setSleepTimeInMillis(int time) {
	//		m_sleepTimeInMillis = time;
	//	}

	private int getSleepTimeInMillis() {
		return m_sleepTimeInMillis;
	}


	private void setLdsServiceProducerPool(ProducerPool pool) {
		m_ldsServiceProducerPool = pool;
	}

	private ProducerPool getLdsServiceProducerPool() {
		return m_ldsServiceProducerPool;
	}


	private void setGroupDnTemplate (String template) throws 
	StepException {

		if (template == null) {
			String errMsg = "groupDnTemplate property is null. " +
					"Can't continue.";
			throw new StepException(errMsg);
		}

		m_groupDnTemplate = template;
	}

	private String getGroupDnTemplate() {
		return m_groupDnTemplate;
	}

	private String buildDnValueFromTemplate(String accountId) {
		String dn = getGroupDnTemplate()
				.replace("ACCOUNT_NUMBER", accountId);
		return dn;
	}

}
