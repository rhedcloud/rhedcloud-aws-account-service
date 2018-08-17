/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2018 Emory University. All rights reserved. 
 ******************************************************************************/

package edu.emory.awsaccount.service.provider;

// Java utilities
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;

import javax.jms.JMSException;

// Log4j
import org.apache.log4j.Category;

// OpenEAI foundation
import org.openeai.OpenEaiObject;
import org.openeai.config.AppConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.EnterpriseFieldException;
import org.openeai.config.PropertyConfig;
import org.openeai.jms.producer.PointToPointProducer;
import org.openeai.jms.producer.ProducerPool;
import org.openeai.moa.EnterpriseObjectQueryException;
import org.openeai.transport.RequestService;

import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.AccountProvisioningAuthorization;
import com.amazon.aws.moa.objects.resources.v1_0.AccountProvisioningAuthorizationQuerySpecification;
import edu.emory.moa.jmsobjects.identity.v2_0.Employee;
import edu.emory.moa.jmsobjects.identity.v2_0.FullPerson;
import edu.emory.moa.objects.resources.v2_0.FullPersonQuerySpecification;

/**
 * An authorization provider that queries the IdenityService for the FullPerson
 * object to determine if the person is one of the following authorized types:
 * 
 * faculty
 * physician
 * healthCareManager
 * administrator
 * staffStudent
 * staff
 *
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 13 August 2018
 *
 */

public class EmoryAccountProvisioningAuthorizationProvider extends OpenEaiObject implements AccountProvisioningAuthorizationProvider {

    private Category logger = OpenEaiObject.logger;
    private AppConfig m_appConfig;
    private String LOGTAG = "[EmoryAccountProvisioningAuthorizationProvider] ";
    private ProducerPool m_identityServiceProducerPool = null;

    /**
     * @see AccountProvisioningAuthorizationProvider.java
     */
    @Override
    public void init(AppConfig aConfig) throws ProviderException {
        logger.info(LOGTAG + "Initializing...");
        m_appConfig = aConfig;

        // Get the provider properties
        PropertyConfig pConfig = new PropertyConfig();
        try {
            pConfig = (PropertyConfig) aConfig.getObject("AccountProvisioningAuthorizationProviderProperties");
        } catch (EnterpriseConfigurationObjectException eoce) {
            String errMsg = "Error retrieving a PropertyConfig object from " + "AppConfig: The exception is: " + eoce.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, eoce);
        }
        
		// This provider needs to send messages to the IdentityService
		// to query for FullPerson.
		ProducerPool p2p1 = null;
		try {
			p2p1 = (ProducerPool)getAppConfig()
				.getObject("IdentityServiceServiceProducerPool");
			setIdentityServiceProducerPool(p2p1);
		}
		catch (EnterpriseConfigurationObjectException ecoe) {
			// An error occurred retrieving an object from AppConfig. Log it and
			// throw an exception.
			String errMsg = "An error occurred retrieving an object from " +
					"AppConfig. The exception is: " + ecoe.getMessage();
			logger.fatal(LOGTAG + errMsg);
			throw new ProviderException(errMsg);
		}	
		
		// Verify all required message objects.
		// Get an AccountProvisioningAuthoriztion, FullPerson,
		// and a FullPersonQuerySpecification from AppConfig
		AccountProvisioningAuthorization auth = new AccountProvisioningAuthorization();
		FullPerson fullPerson = new FullPerson();
    	FullPersonQuerySpecification querySpec = new FullPersonQuerySpecification();
		try {
			auth = (AccountProvisioningAuthorization) getAppConfig()
				.getObjectByType(auth.getClass().getName());
			fullPerson = (FullPerson)getAppConfig()
				.getObjectByType(fullPerson.getClass().getName());
			querySpec = (FullPersonQuerySpecification)getAppConfig()
				.getObjectByType(querySpec.getClass().getName());
		}
		catch (EnterpriseConfigurationObjectException ecoe) {
			String errMsg = "An error occurred retrieving an object from " +
					"AppConfig. The exception is: " + ecoe.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg, ecoe);
		}

        logger.info(LOGTAG + pConfig.getProperties().toString());

        logger.info(LOGTAG + "Initialization complete.");
    }

    /**
     * @see AccountProvisioningAuthorizationProvider.java
     * 
     *      Note: this implementation queries by AccountId.
     */
    @Override
    public List<AccountProvisioningAuthorization> query(AccountProvisioningAuthorizationQuerySpecification querySpec) throws ProviderException {

    	// Get an AccountProvisioningAuthoriztion, FullPerson,
		// and a FullPersonQuerySpecification from AppConfig
		AccountProvisioningAuthorization auth = new AccountProvisioningAuthorization();
		FullPerson fullPerson = new FullPerson();
    	FullPersonQuerySpecification fullPersonQuerySpec = new FullPersonQuerySpecification();
		try {
			auth = (AccountProvisioningAuthorization) getAppConfig()
				.getObjectByType(auth.getClass().getName());
			fullPerson = (FullPerson)getAppConfig()
				.getObjectByType(fullPerson.getClass().getName());
			fullPersonQuerySpec = (FullPersonQuerySpecification)getAppConfig()
				.getObjectByType(querySpec.getClass().getName());
		}
		catch (EnterpriseConfigurationObjectException ecoe) {
			String errMsg = "An error occurred retrieving an object from " +
					"AppConfig. The exception is: " + ecoe.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg, ecoe);
		}
		
		// Set the values of the FullPerson query spec.
		if (querySpec.getUserId() == null) {
			String errMsg = "The UserId provided in the query " +
				"specification is null. Cannot authorize user.";
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(LOGTAG + errMsg);
		}
		try {
			fullPersonQuerySpec.setPublicId(querySpec.getUserId());
		}
		catch (EnterpriseFieldException efe) {
			String errMsg = "An error occurred setting field values. " +
				"The exception is: " + efe.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(LOGTAG + errMsg);
		}
		
		// Get a RequestService to use for this transaction.
		RequestService rs = null;
		try {
			rs = (RequestService)getIdentityServiceProducerPool().getExclusiveProducer();
		}
		catch (JMSException jmse) {
			String errMsg = "An error occurred getting a request service to use " +
				"in this transaction. The exception is: " + jmse.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg, jmse);
		}
		// Query for the FullPerson.
		List fullPersonList = null;
		try {
			long startTime = System.currentTimeMillis();
			fullPersonList = fullPerson.query(fullPersonQuerySpec, rs);
			long time = System.currentTimeMillis() - startTime;
			logger.info(LOGTAG + "Queried for FullPerson for " +
				"UserId " + fullPersonQuerySpec.getPublicId() + 
				" in " + time + " ms. Returned " + 
				fullPersonList.size() + " FullPerson record(s).");
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
			getIdentityServiceProducerPool().releaseProducer((PointToPointProducer)rs);
    	}

		if (fullPersonList.size() != 1) {
			String errMsg = "An error occurred querying for a FullPerson " +
				"object for UserId " + fullPersonQuerySpec.getPublicId() + 
				" . Exactly one result was expected, but got " + 
				fullPersonList.size() + " result(s).";
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg);
		}
		
		// Evaluate the FullPerson for authorization.
		boolean isAuthorized = false;
		List<String> categories = new ArrayList<String>();
		
		FullPerson person = (FullPerson)fullPersonList.get(0);
		Employee employee = person.getEmployee();
		
		if (employee.getFaculty().equalsIgnoreCase("true")) {
			categories.add("faculty");
			isAuthorized = true;
		}
		if (employee.getPhysician().equalsIgnoreCase("true")) {
			categories.add("physician");
			isAuthorized = true;
		}
		if (employee.getHealthCareManager().equalsIgnoreCase("true")) {
			categories.add("health care manager");
			isAuthorized = true;
		}
		if (employee.getAdministrative().equalsIgnoreCase("true")) {
			categories.add("administrative staff");
			isAuthorized = true;
		}
		if (employee.getStaffStudent().equalsIgnoreCase("true")) {
			categories.add("staff student");
			isAuthorized = true;
		}
		if (employee.getStaff().equalsIgnoreCase("true")) {
			categories.add("staff");
			isAuthorized = true;
		}
		
		// Build the authorization description.
		String authDescription = "Presently faculty, physicians, health care managers, administrative staff, staff students, and staff are authorized to provisiong Emory AWS accounts.";
		if (isAuthorized == false) {
			authDescription = authDescription + " You are not in one of these authorized groups.";
		}
		else {
			authDescription = authDescription + " You are in the following authoirzed group(s): ";
			ListIterator<String> li = categories.listIterator();
			while (li.hasNext()) {	
				String category = (String)li.next();
				if (li.hasNext()) category = category + ", ";
				authDescription = authDescription + category;
				
			}
		}
		
        // Set the values of the AccountProvisioningAuthorization
        try {
            auth.setUserId(querySpec.getUserId());
            auth.setIsAuthorized(Boolean.toString(isAuthorized));
            auth.setAuthorizedUserDescription(authDescription);
            
        } catch (EnterpriseFieldException efe) {
            String errMsg = "An error occurred seting field values. " + "The exception is: " + efe.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, efe);
        }

        // Add the AccountProvisioningAuthorization to a list.
        List<AccountProvisioningAuthorization> authList = 
        	new ArrayList<AccountProvisioningAuthorization>();
        authList.add(auth);
        return authList;

    }

    private AppConfig getAppConfig() {
        return m_appConfig;
    }
    
    private void setIdentityServiceProducerPool(ProducerPool pool) {
    	m_identityServiceProducerPool = pool;
    }
    
    private ProducerPool getIdentityServiceProducerPool() {
    	return m_identityServiceProducerPool;
    }

}
