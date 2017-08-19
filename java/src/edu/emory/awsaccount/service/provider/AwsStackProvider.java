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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Properties;
import java.util.StringTokenizer;

// Log4j
import org.apache.log4j.Category;

// OpenEAI foundation
import org.openeai.OpenEaiObject;
import org.openeai.config.AppConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.EnterpriseFieldException;
import org.openeai.config.PropertyConfig;
import org.openeai.moa.XmlEnterpriseObjectException;

// AWS Message Object API (MOA)
import com.amazon.aws.moa.jmsobjects.cloudformation.v1_0.Stack;
import com.amazon.aws.moa.objects.resources.v1_0.Datetime;
import com.amazon.aws.moa.objects.resources.v1_0.Output;
import com.amazon.aws.moa.objects.resources.v1_0.StackParameter;
import com.amazon.aws.moa.objects.resources.v1_0.StackQuerySpecification;
import com.amazon.aws.moa.objects.resources.v1_0.StackRequisition;

// AWS APIs
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.cloudformation.AmazonCloudFormation;
import com.amazonaws.services.cloudformation.AmazonCloudFormationClient;
import com.amazonaws.services.cloudformation.AmazonCloudFormationClientBuilder;
import com.amazonaws.services.cloudformation.model.Capability;
import com.amazonaws.services.cloudformation.model.DescribeStacksRequest;
import com.amazonaws.services.cloudformation.model.DescribeStacksResult;
import com.amazonaws.services.cloudformation.model.Parameter;
import com.amazonaws.services.cloudformation.model.StackStatus;
import com.amazonaws.services.cloudformation.model.Tag;
import com.amazonaws.services.cloudformation.model.CreateStackRequest;
import com.amazonaws.services.cloudformation.model.CreateStackResult;
import com.amazonaws.services.cloudformation.model.DeleteStackRequest;
import com.amazonaws.services.cloudformation.model.DeleteStackResult;

/**
 *  An object provider that creates, updates, and deletes stacks
 *  in AWS.
 *
 * @author Steve Wheat (swheat@emory.edu)
 *
 */
public class AwsStackProvider extends OpenEaiObject 
implements StackProvider {

	private Category logger = OpenEaiObject.logger;
	private AppConfig m_appConfig;
	private HashMap<String, AmazonCloudFormationClient> m_clientMap = 
		new HashMap<String, AmazonCloudFormationClient>();
	private boolean m_verbose = false;
	private long m_waitInterval = 5 * 1000; // wait interval
	private long m_maxWaitTime = 10 * 60 * 1000; // max wait time
	
	private AmazonCloudFormationClient m_client = null;
	private String LOGTAG = "[AwsStackProvider] ";
	
	/**
	 * @see VirtualPrivateCloudProvider.java
	 */
	@Override
	public void init(AppConfig aConfig) throws ProviderException {
		logger.info(LOGTAG + "Initializing...");
		m_appConfig = aConfig;

		// Get the provider properties
		PropertyConfig pConfig = new PropertyConfig();
		try {
			pConfig = (PropertyConfig)aConfig
					.getObject("AwsStackProviderProperties");
		} 
		catch (EnterpriseConfigurationObjectException eoce) {
			String errMsg = "Error retrieving a PropertyConfig object from "
					+ "AppConfig: The exception is: " + eoce.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg, eoce);
		}
		
		// Set the provider properties
		setProperties(pConfig.getProperties());
		logger.info(LOGTAG + getProperties().toString());
		
		// Set the verbose property
		setVerbose(Boolean.valueOf(getProperties().getProperty("verbose", "false")));
		logger.info(LOGTAG + "Verbose propery is: " + getVerbose());
		
		// Set the waitInterval property
		setWaitInterval(Long.valueOf(getProperties().getProperty("waitInterval", "5000")));
		logger.info(LOGTAG + "waitInterval property is: " + getWaitInterval() + " ms.");
		
		// Set the maxWaitTime property
		setMaxWaitTime(Long.valueOf(getProperties().getProperty("maxWaitTime", "600000")));
		logger.info(LOGTAG + "maxWaitTime property is: " + getMaxWaitTime() + " ms.");
		
		// Get AWS credentials for all accounts in the inventory.
		List awsAccountCredentials = null;
		try {
			awsAccountCredentials = getAppConfig()
				.getObjectsLike("AwsAccountCredentials");
			logger.info(LOGTAG + "Found: " + awsAccountCredentials.size() + 
				" account credentials.");
		}
		catch (EnterpriseConfigurationObjectException ecoe) {
			String errMsg = "An error occurred retrieving AWS account " +
				"credentials from AppConfig. The exception is: " + 
				ecoe.getMessage();
			throw new ProviderException(ecoe.getMessage(), ecoe);
		}
		
		// Instantiate an AWS client for each account.
		ListIterator it = awsAccountCredentials.listIterator();
		while (it.hasNext()) {
			PropertyConfig awsConfig = (PropertyConfig)it.next();
			Properties awsProps = awsConfig.getProperties();
			String accountEmail = awsProps.getProperty("accountEmail");
			String accountId = awsProps.getProperty("accountId");
			String accessKey = awsProps.getProperty("accessKey");
			String secretKey = awsProps.getProperty("secretKey");
			
			logger.info(LOGTAG + "Creating AWS client for account " +
				accountEmail + " (" + accountId + ")...");
			
			// Set the AWS account credentials
			BasicAWSCredentials creds = new BasicAWSCredentials(accessKey, 
				secretKey);
			
			// Instantiate an AWS client builder
			AmazonCloudFormationClientBuilder builder = AmazonCloudFormationClientBuilder
					.standard().withCredentials(new AWSStaticCredentialsProvider(creds));
			builder.setRegion("us-east-1");
			
			// Initialize the AWS client
			logger.info("Initializing AmazonCloudFormationClient...");
			AmazonCloudFormationClient client = (AmazonCloudFormationClient)builder.build();
			logger.info("AmazonCloudFormationClient initialized for account " +
				accountEmail + " (" + accountId + ")...");
			addCloudFormationClient(accountId, client);
			
			// List stacks in the account to confirm connectivity
			String stackList = client.listStacks().toString();
			logger.info(LOGTAG + "Account has the following stacks: " + stackList);	
		}

		logger.info(LOGTAG + "Initialization complete.");
	}

	/**
	 * @see StackProvider.java
	 * 
	 * Note: this implementation queries by StackId.
	 */
	public List<Stack> query(StackQuerySpecification querySpec)
			throws ProviderException {

		// If the StackId is null, throw an exception.
		if (querySpec.getStackName() == null || 
			querySpec.getStackName().equals("")) {
			
			String errMsg = "The StackName is null. The AwsStackProvider" +
				"presently only implements query by StackName.";
			throw new ProviderException(errMsg);
		}
		
		// Build the DescribeStacksRequest which is the query
		// object the AWS API requires
		DescribeStacksRequest request = new DescribeStacksRequest();
		request.setStackName(querySpec.getStackName());
		logger.info(LOGTAG + "Querying for stack named: " + 
			querySpec.getStackName());
		long startTime = System.currentTimeMillis();
		DescribeStacksResult result = null;
		try {
			result = getCloudFormationClient(querySpec.getAccountId())
				.describeStacks(request);
		}
		catch (Exception e) {
			String errMsg = "An error occurred querying AWS for the " +
				"CloudFormation Stack. The exception is: " + e.getMessage();
			logger.error(LOGTAG + errMsg);
			if (!e.getMessage().contains("400"))  {
				logger.info(LOGTAG + "This is not a stack not present " + 
					"exception and we need to throw it.");
				throw new ProviderException(errMsg, e);
			}
			else {
				logger.info(LOGTAG + "This is only a stack not present " +
					"exception and it should be ignored. Returning an " +
					"list of stacks.");
				return new ArrayList();
			}
		}
		long time = System.currentTimeMillis() - startTime;
		if (result != null) {
			List awsStacks = result.getStacks();
			if (awsStacks.size() == 1) {
				logger.info(LOGTAG + "Received response from AWS in " + time +
					" ms. There is " + awsStacks.size() + " result.");
			}
			else {
				logger.info(LOGTAG + "Received response from AWS in " + time +
					" ms. There are " + awsStacks.size() + " results.");
			}
			com.amazonaws.services.cloudformation.model.Stack awsStack = 
				(com.amazonaws.services.cloudformation.model.Stack)awsStacks.get(0);	
			
			// If there is no match, return null.
			if (awsStack == null) return null;
			
			// Otherwise build the AEO Stack from the AWS Stack,
			// add it to a list and return it.
			else {
				Stack aeoStack = toAeoStack(awsStack);
				List<Stack> stackList = new ArrayList();
				stackList.add(aeoStack);
				return stackList;
			}
		}
		else {
			return new ArrayList();
		}
	}

	/**
	 * @see StackProvider.java
	 */
	public Stack generate(StackRequisition req)
			throws ProviderException {
		
		// Create a CreateStackRequest to submit to CloudFormation
		CreateStackRequest csr = new CreateStackRequest();
		
		// Set the values of the CreateStackRequest
		// Simple fields
		csr.setStackName(req.getStackName());
		csr.setStackPolicyBody(req.getStackPolicyBody());
		csr.setStackPolicyURL(req.getStackPolicyUrl());
		csr.setTemplateBody(req.getTemplateBody());
		csr.setTemplateURL(req.getTemplateUrl());
		csr.setRoleARN(req.getRoleArn());
		if (req.getTimeoutInMinutes() != null) {
			csr.setTimeoutInMinutes(Integer.valueOf(req.getTimeoutInMinutes())
			.intValue());
		}
		csr.setOnFailure(req.getOnFailure());
		csr.setDisableRollback(Boolean.valueOf(req.getDisableRollback())
			.booleanValue());
		csr.setNotificationARNs(req.getNotificationArn());
		//csr.setResourceTypes(req.getResourceType());
		csr.setCapabilities(req.getCapability());
		
		// Parameters List
		List eoParms = req.getStackParameter();
		List awsParms = new ArrayList();
		ListIterator eoParmsIterator = eoParms.listIterator();
		while (eoParmsIterator.hasNext()) {
			StackParameter eoParm = (StackParameter)eoParmsIterator.next();
			Parameter awsParm = new Parameter();
			awsParm.setParameterKey(eoParm.getKey());
			awsParm.setParameterValue(eoParm.getValue());
			awsParms.add(awsParm);
		}
		csr.setParameters(awsParms);
		
		// Tags List
		List eoTags = req.getTag();
		List awsTags = new ArrayList();
		ListIterator eoTagsIterator = eoTags.listIterator();
		while (eoTagsIterator.hasNext()) {
			com.amazon.aws.moa.objects.resources.v1_0.Tag eoTag = 
				(com.amazon.aws.moa.objects.resources.v1_0.Tag)eoTagsIterator.next();
			Tag awsTag = new Tag();
			awsTag.setKey(eoTag.getKey());
			awsTag.setValue(eoTag.getValue());
			awsTags.add(awsTag);
		}
		csr.setTags(awsTags);
		
		// Submit the stack request.
		logger.info(LOGTAG + "Requesting the stack be created with stack " +
			"request: " + csr.toString());
		long startTime = System.currentTimeMillis();
		CreateStackResult result = null;
		try{
			result = getCloudFormationClient(req.getAccountId())
				.createStack(csr);
			// If the request does not want an immediate response,
			// wait for completion
			if (true) {
				StackQuerySpecification querySpec = new StackQuerySpecification();
				try {
					querySpec.setStackName(req.getStackName());
					querySpec.setAccountId(req.getAccountId());
				}
				catch (EnterpriseFieldException efe) {
					String errMsg = "An error occurred setting values of " +
						"the StackQuerySpecification to query for the " +
						"status of the stack during creation. The exception " +
						"is: " + efe.getMessage();
					throw new ProviderException(errMsg, efe);
				}
				waitForCompletion(querySpec, "generate");
			}
		}
		catch (Exception e) {
			String errMsg = "An error occurred creating the stack. " +
				"The exception is: " + e.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg, e);
		}
		long time = System.currentTimeMillis() - startTime;
		logger.info(LOGTAG + "Completed stack request in " + time + 
			"ms. Result is: " + result.toString());
		
		// Build a Query Specification to Query for the Stack
		StackQuerySpecification querySpec = new StackQuerySpecification();
		try {
			querySpec.setStackName(req.getStackName());
			querySpec.setAccountId(req.getAccountId());
		}
		catch (EnterpriseFieldException efe) {
			logger.error(LOGTAG + efe.getMessage());
			throw new ProviderException(efe.getMessage());
		}
		
		// Query for the Stack
		List stacks = query(querySpec);
		
		// Return the first stack in the list.
		return (Stack)stacks.get(0);
	}

	/**
	 * @see StackProvider.java
	 */
	public void update(Stack stack) throws ProviderException {		
		
		String errMsg = "Update action not yet implemented.";
		throw new ProviderException(errMsg);
	}
	
	/**
	 * @see StackProvider.java
	 */
	public void delete(Stack stack) throws ProviderException {		
		
		// Create a DeleteStackRequest to submit to CloudFormation
		DeleteStackRequest dsr = new DeleteStackRequest();
		
		// Set the values of the StackName
		dsr.setStackName(stack.getStackName());
		
		// Get the accountId
		String accountId = parseAccountIdFromStackId(stack.getStackId());
		
		// Submit the stack request.
		logger.info(LOGTAG + "Requesting the stack be deleted with stack " +
			"request: " + dsr.toString() + " and accountId: " + accountId);
		long startTime = System.currentTimeMillis();
		DeleteStackResult result = null;
		try {
			result = getCloudFormationClient(accountId).deleteStack(dsr);
			// If the request does not want an immediate response,
			// wait for completion
			if (true) {
				StackQuerySpecification querySpec = new StackQuerySpecification();
				try {
					querySpec.setStackName(stack.getStackName());
					querySpec.setAccountId(accountId);
				}
				catch (EnterpriseFieldException efe) {
					String errMsg = "An error occurred setting values of " +
						"the StackQuerySpecification to query for the " +
						"status of the stack during deletion. The exception " +
						"is: " + efe.getMessage();
					throw new ProviderException(errMsg, efe);
				}
				waitForCompletion(querySpec, "delete");
			}
		}
		catch (Exception e) {
			String errMsg = "An error occurred deleting the stack. " +
				"The exception is: " + e.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new ProviderException(errMsg, e);
		}
		long time = System.currentTimeMillis() - startTime;
		logger.info(LOGTAG + "Completed stack request in " + time + 
			"ms. Result is: " + result.toString());

		return;
	}
	
	/**
	 * @param AmazonCloudFormationClient, the interface to Amazon 
	 * CloudFormation
	 * <P>
	 * This method adds an interface object to Amazon CloudFormation
	 */
	private void addCloudFormationClient(String accountId, 
		AmazonCloudFormationClient client) {
		
		m_clientMap.put(accountId, client);
	}

	/**
	 * @return AmazonCloudFormationClient, the interface to Amazon 
	 * CloudFormation
	 * <P>
	 * This method returns the interface to Amazon CloudFormation
	 */
	private AmazonCloudFormationClient getCloudFormationClient(String accountId) {
		return m_clientMap.get(accountId);
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
	 * @param long, the wait interval in milliseconds
	 * <P>
	 * This method sets the wait interval
	 */
	private void setWaitInterval(long waitInterval) {
		m_waitInterval = waitInterval;
	}

	/**
	 * @return long, the wait interval in milliseconds
	 * <P>
	 * This method returns the wait interval
	 */
	private long getWaitInterval() {
		return m_waitInterval;
	}
	
	/**
	 * @param long, the maximum time to wait for CloudFormation
	 * templates to be created or deleted
	 * <P>
	 * This method sets the maximum wait time property
	 */
	private void setMaxWaitTime(long maxWaitTime) {
		m_maxWaitTime = maxWaitTime;
	}

	/**
	 * @return long, the maximum time to wait for CloudFormation
	 * templates to be created or deleted
	 * <P>
	 * This method returns the maximum wait time property
	 */
	private long getMaxWaitTime() {
		return m_maxWaitTime;
	}
	
	private String parseAccountIdFromStackId(String stackId)
		throws ProviderException {
		String LOGTAG = "[AwsStackProvider.parseAccountIdFromStackId] ";
		StringTokenizer st = new StringTokenizer(stackId, ":");
		int i = 0;
		String accountId = null;
		while (st.hasMoreTokens()) {
			i++;
			String token = st.nextToken();
			logger.info(LOGTAG + "Token " + i + ": " + 
				token);
			if (i == 5) accountId = token;
		}
		if (accountId == null) {
			String errMsg = "No AccountId parsed from StackId.";
			throw new ProviderException(errMsg);
		}
		else {
			return accountId;
		}
	}
	
	/**
	 * @return Stack, the actionable enterprise object for Stack 
	 * in the AWS MOA
	 * <P>
	 * This method returns the AEO Stack
	 */
	private Stack toAeoStack(com.amazonaws.services.cloudformation.model.Stack 
		awsStack) throws ProviderException {
		// Get a configured Stack object from AppConfig
		Stack aeoStack = 
			new com.amazon.aws.moa.jmsobjects.cloudformation.v1_0.Stack();
        try {
            aeoStack = (Stack) getAppConfig()
            	.getObjectByType(aeoStack.getClass().getName());
        } catch (EnterpriseConfigurationObjectException eoce) {
            String errMsg = "Error retrieving an object from AppConfig: " +
            	"The exception" + "is: " + eoce.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg);
        }
        
        // Set the values of Stack
        try {
        	// Simple fields
        	aeoStack.setStackId(awsStack.getStackId());
        	aeoStack.setStackName(awsStack.getStackName());
        	aeoStack.setDescription(awsStack.getDescription());
        	aeoStack.setCreateDatetime(new Datetime("Create", 
        		awsStack.getCreationTime()));
        	aeoStack.setStackStatus(awsStack.getStackStatus());
        	
        	// Parameter list
        	List awsParameters = awsStack.getParameters();
        	ListIterator awsParameterLi = awsParameters.listIterator();
        	while (awsParameterLi.hasNext()) {
        		Parameter awsParm = (Parameter)awsParameterLi.next();
        		StackParameter eoParm = aeoStack.newStackParameter();
        		eoParm.setKey(awsParm.getParameterKey());
        		eoParm.setValue(awsParm.getParameterValue());
        		aeoStack.addStackParameter(eoParm);
        	}
        	
        	// Tag list
        	List awsTags = awsStack.getTags();
        	ListIterator awsTagsLi = awsTags.listIterator();
        	while (awsTagsLi.hasNext()) {
        		com.amazonaws.services.cloudformation.model.Tag awsTag = 
        			(com.amazonaws.services.cloudformation.model.Tag)awsTagsLi.next();
        		com.amazon.aws.moa.objects.resources.v1_0.Tag eoTag = 
        			aeoStack.newTag();
        		eoTag.setKey(awsTag.getKey());
        		eoTag.setValue(awsTag.getValue());
        		aeoStack.addTag(eoTag);
        	}
        	
        	// Capability list
        	List awsCapabilities = awsStack.getCapabilities();
        	ListIterator awsCapabilitiesLi = awsCapabilities.listIterator();
        	while (awsCapabilitiesLi.hasNext()) {
        		String sCapability = (String)awsCapabilitiesLi.next();
        		aeoStack.addCapability(sCapability);
        	}	
        	
        	// Disable rollback boolean
        	if (awsStack.getDisableRollback()) {
        		aeoStack.setDisableRollback("true");
        	}
        	else {
        		aeoStack.setDisableRollback("false");
        	}
		
        	// Outputs list
        	List awsOutputs = awsStack.getOutputs();
        	List<Output> eoOutputs = new ArrayList();
        	ListIterator li = awsOutputs.listIterator();
        	while (li.hasNext()) {
        		com.amazonaws.services.cloudformation.model.Output awsOutput = 
        			(com.amazonaws.services.cloudformation.model.Output)li.next();
        		Output eoOutput = aeoStack.newOutput();
        		eoOutput.setDescription(awsOutput.getDescription());
        		eoOutput.setOutputKey(awsOutput.getOutputKey());
        		eoOutput.setOutputValue(awsOutput.getOutputValue());
        		eoOutputs.add(eoOutput);
        	}
        	aeoStack.setOutput(eoOutputs);		
        }
        catch (EnterpriseFieldException efe) {
        	String errMsg = "Error setting field values for the Stack object/ " +
                	"The exception" + "is: " + efe.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new ProviderException(errMsg);
        }
		
        if (true) {
        	try { 
        		String xmlStack = aeoStack.toXmlString();
        		if (getVerbose()) logger.info(LOGTAG + "Built aeoStack: " + 
        			xmlStack);
        		if (getVerbose()) logger.info(LOGTAG + "From awsStack: " +
        			awsStack.toString());
        	}
        	catch (XmlEnterpriseObjectException xeoe) {
        		String errMsg = "An error occurred serializing the Stack " +
        			"object to XML. The exception is: " + xeoe.getMessage();
        	}
        }
   
		return aeoStack;
	}
	
	/**
	 * @return AppConfig, the AppConfig for this object
	 * <P>
	 * This method returns the AppConfig for this object
	 */
	private AppConfig getAppConfig() {
		return m_appConfig;
	}
	
	private void waitForCompletion(StackQuerySpecification querySpec, String action) 
		throws ProviderException {
		
		String LOGTAG = "[AwsStackProvider.waitForCompletion] ";
		boolean isCompleted = false;
		long startTime = System.currentTimeMillis();
		while (isCompleted == false) {
			// Throw an exception if maxWaitTime is exceeded.
			if (System.currentTimeMillis() - startTime > getMaxWaitTime()) {
				String errMsg = "Maximum wait time of " + getWaitInterval() + 
					" ms for completion of CloudFormation template has been " 
					+ "exceeded. Template may still be in progress.";
				throw new ProviderException(errMsg);
			}
			
			
			List stacks = query(querySpec);
			if (stacks.size() == 0 && action.equalsIgnoreCase("delete")) {
				logger.info(LOGTAG + "Stack named " + querySpec.getStackName() + 
					" not found in account " + querySpec.getAccountId() + 
					" and must already be deleted.");
				return;
			}
			Stack stack = (Stack)stacks.get(0);
			if (stack != null) {
				logger.info(LOGTAG + "Found Stack named " + stack.getStackName() +
					" with status " + stack.getStackStatus());
				if (action.equalsIgnoreCase("generate")) {
					if (stack.getStackStatus().equals(StackStatus.CREATE_COMPLETE.toString()) ||
	                        stack.getStackStatus().equals(StackStatus.CREATE_FAILED.toString()) ||
	                        stack.getStackStatus().equals(StackStatus.ROLLBACK_FAILED.toString()) ||
	                        stack.getStackStatus().equals(StackStatus.ROLLBACK_COMPLETE.toString()) ||
	                        stack.getStackStatus().equals(StackStatus.DELETE_FAILED.toString())) {
						isCompleted = true;
						break;
					}
				}
				if (action.equalsIgnoreCase("delete")) {
					if (stack.getStackStatus().equals(StackStatus.DELETE_COMPLETE.toString()) ||
	                        stack.getStackStatus().equals(StackStatus.DELETE_FAILED.toString()) ||
	                        stack.getStackStatus().equals(StackStatus.ROLLBACK_FAILED.toString())) {
						isCompleted = true;
						break;
					}
				}
				else {
					logger.info(LOGTAG + "Sleeping for " + getWaitInterval() 
						+ "ms...");
					try {
						Thread.sleep(getWaitInterval());
					}
					catch (InterruptedException ie) {
						String errMsg = "An error occurred " +
							"to check the status of the stack. The " +
							"exception is: " + ie.getMessage();
						throw new ProviderException(errMsg, ie);
					}
				}	
			}
			else {
				logger.info(LOGTAG + "No stack named " + 
					querySpec.getStackName() + 
					" found. Returing from wait for completion.");
				isCompleted = true;
			}	
		}	
	}
}
