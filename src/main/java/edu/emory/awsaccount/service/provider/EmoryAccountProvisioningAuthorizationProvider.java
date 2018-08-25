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
import java.util.Properties;

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

import edu.emory.awsaccount.service.provider.step.StepException;
import edu.emory.moa.jmsobjects.identity.v1_0.RoleAssignment;
import edu.emory.moa.jmsobjects.identity.v2_0.Employee;
import edu.emory.moa.jmsobjects.identity.v2_0.FullPerson;
import edu.emory.moa.objects.resources.v1_0.RoleAssignmentQuerySpecification;
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
    private ProducerPool m_idmServiceProducerPool = null;
    private String m_userDnTemplate = null;
    private String m_roleDn = null;
    private Properties m_props = null;

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
            Properties props = pConfig.getProperties();
            setProperties(props);
        } catch (EnterpriseConfigurationObjectException eoce) {
            String errMsg = "Error retrieving a PropertyConfig object from " + "AppConfig: The exception is: " + eoce.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, eoce);
        }
        
        // Set the value of the userDnTemplate
        String userDnTemplate = getProperties().getProperty("userDnTemplate", null);
        setUserDnTemplate(userDnTemplate);
        
        // Set the value of the roleDn
        String roleDn = getProperties().getProperty("roleDn", null);
        setRoleDn(roleDn);
        
		// This provider needs to send messages to the IdentityService
		// to query for FullPerson.
		ProducerPool p2p1 = null;
		try {
			p2p1 = (ProducerPool)getAppConfig()
				.getObject("IdentityServiceProducerPool");
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
		
		// This provider needs to send messages to the IdmService
		// to query for RoleAssignment.
		ProducerPool p2p2 = null;
		try {
			p2p2 = (ProducerPool)getAppConfig()
				.getObject("IdmServiceProducerPool");
			setIdmServiceProducerPool(p2p2);
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
    	FullPersonQuerySpecification fullPersonQuerySpec = new FullPersonQuerySpecification();
    	RoleAssignment roleAssignment = new RoleAssignment();
    	RoleAssignmentQuerySpecification roleAssignmentQuerySpec = new RoleAssignmentQuerySpecification();
		try {
			auth = (AccountProvisioningAuthorization) getAppConfig()
				.getObjectByType(auth.getClass().getName());
			fullPerson = (FullPerson)getAppConfig()
				.getObjectByType(fullPerson.getClass().getName());
			fullPersonQuerySpec = (FullPersonQuerySpecification)getAppConfig()
				.getObjectByType(fullPersonQuerySpec.getClass().getName());
			roleAssignment = (RoleAssignment)getAppConfig()
					.getObjectByType(roleAssignment.getClass().getName());
			roleAssignmentQuerySpec = (RoleAssignmentQuerySpecification)getAppConfig()
					.getObjectByType(roleAssignmentQuerySpec.getClass().getName());
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
    	RoleAssignment roleAssignment = new RoleAssignment();
    	RoleAssignmentQuerySpecification roleAssignmentQuerySpec = new RoleAssignmentQuerySpecification();
		try {
			auth = (AccountProvisioningAuthorization) getAppConfig()
				.getObjectByType(auth.getClass().getName());
			fullPerson = (FullPerson)getAppConfig()
				.getObjectByType(fullPerson.getClass().getName());
			fullPersonQuerySpec = (FullPersonQuerySpecification)getAppConfig()
				.getObjectByType(fullPersonQuerySpec.getClass().getName());
			roleAssignment = (RoleAssignment)getAppConfig()
					.getObjectByType(roleAssignment.getClass().getName());
			roleAssignmentQuerySpec = (RoleAssignmentQuerySpecification)getAppConfig()
					.getObjectByType(roleAssignmentQuerySpec.getClass().getName());
		}
		catch (EnterpriseConfigurationObjectException ecoe) {
			String errMsg = "An error occurred retrieving an object from " +
					"AppConfig. The exception is: " + ecoe.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg, ecoe);
		}
		
		// Set the values of the RoleAssignment query spec.
		if (querySpec.getUserId() == null) {
			String errMsg = "The UserId provided in the query " +
				"specification is null. Cannot authorize user.";
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(LOGTAG + errMsg);
		}
		try {
			roleAssignmentQuerySpec.setRoleDN(getRoleDn());
		}
		catch (EnterpriseFieldException efe) {
			String errMsg = "An error occurred setting field values. " +
				"The exception is: " + efe.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(LOGTAG + errMsg);
		}
		
		// Get the UserId
		String userId = querySpec.getUserId();
		if (userId == null) {
			String errMsg = "The UserId field is null. " +
				"Cannot authorize user.";
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(LOGTAG + errMsg);
		}
		
		// Build the UserDN
		String userDn = getUserDn(userId);
		
		// Set the values of the querySpec.
		try {
			roleAssignmentQuerySpec.setUserDN(userDn);
			roleAssignmentQuerySpec.setIdentityType("USER");
			roleAssignmentQuerySpec.setDirectAssignOnly("true");
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
		// Query for the RoleAssignments for the user.
		List<RoleAssignment> roleAssignments = null;
		try {
			long startTime = System.currentTimeMillis();
			roleAssignments = roleAssignment.query(roleAssignmentQuerySpec, rs);
			long time = System.currentTimeMillis() - startTime;
			logger.info(LOGTAG + "Queried for RoleAssignments for " +
				"userDn " + userDn + " in " + time + " ms. Returned " + 
				roleAssignments.size() + " RoleAssignments for user.");
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
		
		// Evaluate whether the user is whitelisted for provisioning.
		boolean isWhitelisted = false;
		
		// Get the RoleAssignment to evaluate for
		if (roleAssignments != null) {
			boolean isUserInRole = isUserInRole(getRoleDn(), roleAssignments);
			if (isUserInRole == true) isWhitelisted = true;
		}
		
		// If the user is whitelisted, set the values of the 
		// AccountProvisioningAuthorization and then return it.
		if (isWhitelisted) {
			// Set the values of the AccountProvisioningAuthorization
	        try {
	            auth.setUserId(querySpec.getUserId());
	            auth.setIsAuthorized("true");
	            String authDescription = "User is a member of the Emory AWS " +
	            	"Service provisioning role.";
	            auth.setAuthorizedUserDescription(authDescription);
	            
	        } catch (EnterpriseFieldException efe) {
	            String errMsg = "An error occurred seting field values. " + 
	            	"The exception is: " + efe.getMessage();
	            logger.error(LOGTAG + errMsg);
	            throw new ProviderException(errMsg, efe);
	        }

	        // Add the AccountProvisioningAuthorization to a list.
	        List<AccountProvisioningAuthorization> authList = 
	        	new ArrayList<AccountProvisioningAuthorization>();
	        authList.add(auth);
	        return authList;
		}
		
		// Otherwise, query for FullPerson and evaluate it.
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
		rs = null;
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
		if (employee != null) {
			if (employee.getFaculty() != null) {
				if (employee.getFaculty().equalsIgnoreCase("true")) {
					categories.add("faculty");
					isAuthorized = true;
				}
			}
			if (employee.getPhysician() != null) { 
				if (employee.getPhysician().equalsIgnoreCase("true")) {
					categories.add("physician");
					isAuthorized = true;
				}
	    	}
			if (employee.getHealthCareManager() != null) {
				if (employee.getHealthCareManager().equalsIgnoreCase("true")) {
					categories.add("health care manager");
					isAuthorized = true;
				}
			}
			if (employee.getAdministrative() != null) { 
				if (employee.getAdministrative().equalsIgnoreCase("true")) {
					categories.add("administrative staff");
					isAuthorized = true;
				}
			}
			if (employee.getStaffStudent() != null) {
				if (employee.getStaffStudent().equalsIgnoreCase("true")) {
					categories.add("staff/student");
					isAuthorized = true;
				}
			}
			if (employee.getStaff() != null) {
				if (employee.getStaff().equalsIgnoreCase("true")) {
					categories.add("staff");
					isAuthorized = true;
				}
			}
		}
		
		// Build the authorization description.
		String authDescription = "Presently faculty, physicians, health care managers, administrative staff, staff/students, and staff are authorized to provision Emory AWS accounts.";
		if (isAuthorized == false) {
			authDescription = authDescription + " User is not in any of these authorized groups.";
		}
		else {
			String groupWord = "group";
			if (categories.size() > 1) groupWord = "groups";
			authDescription = authDescription + " User is in the following authorized " + groupWord +": ";
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
            String errMsg = "An error occurred seting field values. " +
            	"The exception is: " + efe.getMessage();
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
    
    private void setIdmServiceProducerPool(ProducerPool pool) {
    	m_idmServiceProducerPool = pool;
    }
    
    private ProducerPool getIdmServiceProducerPool() {
    	return m_idmServiceProducerPool;
    }
    
	private void setUserDnTemplate(String template) throws ProviderException {
			
			String LOGTAG =  
				"[EmoryAccountProvisioningAuthorizationProvider.setUserDnTemplate] ";
			
			if (template == null) {
				String errMsg = "userDnTemplate property is null. " +
					"Can't authorize users.";
				logger.error(LOGTAG + errMsg);
				throw new ProviderException(errMsg);
			}
			m_userDnTemplate = template;
	}
		
	private String getUserDnTemplate() {
		return m_userDnTemplate;
	}
	
	private String getUserDn(String userId) {
		String userDn = getUserDnTemplate()
			.replace("USER_ID", userId);
		return userDn;
	}
	
	private void setRoleDn(String roleDn) {
		m_roleDn = roleDn;
	}
	
	private String getRoleDn() {
		return m_roleDn;
	}
	
	private boolean isUserInRole(String roleDn, List<RoleAssignment> roleAssignments) {
		
		boolean isUserInRole = false;
		
		ListIterator li = roleAssignments.listIterator();
		while (li.hasNext()) {
			RoleAssignment ra = (RoleAssignment)li.next();
			if (ra.getRoleDN().equalsIgnoreCase(getRoleDn())) {
				isUserInRole = true;
			}
		}
		
		return isUserInRole;
	}

}
