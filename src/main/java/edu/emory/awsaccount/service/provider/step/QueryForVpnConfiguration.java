/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2017 Emory University. All rights reserved. 
 ******************************************************************************/
package edu.emory.awsaccount.service.provider.step;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.Properties;

import javax.jms.JMSException;

import org.apache.commons.net.util.SubnetUtils;
import org.apache.commons.net.util.SubnetUtils.SubnetInfo;
import org.jdom.Document;
import org.jdom.Element;
import org.jdom.JDOMException;
import org.jdom.input.SAXBuilder;
import org.openeai.config.AppConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.EnterpriseFieldException;
import org.openeai.jms.producer.MessageProducer;
import org.openeai.jms.producer.ProducerPool;
import org.openeai.moa.EnterpriseObjectDeleteException;
import org.openeai.moa.EnterpriseObjectQueryException;
import org.openeai.moa.XmlEnterpriseObjectException;
import org.openeai.transport.RequestService;

import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.Account;
import com.amazon.aws.moa.objects.resources.v1_0.AccountQuerySpecification;
import com.amazon.aws.moa.objects.resources.v1_0.Property;
import com.amazon.aws.moa.objects.resources.v1_0.ProvisioningStep;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.BasicSessionCredentials;
import com.amazonaws.services.cloudformation.AmazonCloudFormationClient;
import com.amazonaws.services.cloudformation.AmazonCloudFormationClientBuilder;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.DescribeVpnConnectionsRequest;
import com.amazonaws.services.ec2.model.DescribeVpnConnectionsResult;
import com.amazonaws.services.ec2.model.VpnConnection;
import com.amazonaws.services.organizations.AWSOrganizationsClient;
import com.amazonaws.services.organizations.AWSOrganizationsClientBuilder;
import com.amazonaws.services.organizations.model.CreateAccountRequest;
import com.amazonaws.services.organizations.model.CreateAccountResult;
import com.amazonaws.services.organizations.model.CreateAccountStatus;
import com.amazonaws.services.organizations.model.DescribeCreateAccountStatusRequest;
import com.amazonaws.services.organizations.model.DescribeCreateAccountStatusResult;
import com.amazonaws.services.organizations.model.ListAccountsForParentRequest;
import com.amazonaws.services.organizations.model.ListAccountsForParentResult;
import com.amazonaws.services.organizations.model.ListAccountsRequest;
import com.amazonaws.services.organizations.model.ListAccountsResult;
import com.amazonaws.services.organizations.model.MoveAccountRequest;
import com.amazonaws.services.organizations.model.MoveAccountResult;
import com.amazonaws.services.securitytoken.AWSSecurityTokenService;
import com.amazonaws.services.securitytoken.AWSSecurityTokenServiceClientBuilder;
import com.amazonaws.services.securitytoken.model.AssumeRoleRequest;
import com.amazonaws.services.securitytoken.model.AssumeRoleResult;
import com.amazonaws.services.securitytoken.model.Credentials;

import edu.emory.awsaccount.service.provider.ProviderException;
import edu.emory.awsaccount.service.provider.VirtualPrivateCloudProvisioningProvider;
import edu.emory.moa.jmsobjects.validation.v1_0.EmailAddressValidation;
import edu.emory.moa.objects.resources.v1_0.EmailAddressValidationQuerySpecification;

/**
 * If this is a new account request, create the account.
 * <P>
 * 
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 17 August 2018
 **/
public class QueryForVpnConfiguration extends AbstractStep implements Step {
	
	private final static String IN_PROGRESS = "IN_PROGRESS";
	private final static String SUCCEEDED = "SUCCEEDED";
	private final static String FAILED = "FAILED";
	private String m_accessKeyId = null;
	private String m_secretKey = null;
	private String m_roleArnPattern = null;
	private int m_roleAssumptionDurationSeconds = 0;
	private AmazonEC2Client m_client = null;
	private String LOGTAG = "[QueryForVpnConfiguration] ";

	public void init (String provisioningId, Properties props, 
			AppConfig aConfig, VirtualPrivateCloudProvisioningProvider vpcpp) 
			throws StepException {
		
		super.init(provisioningId, props, aConfig, vpcpp);
		
		String LOGTAG = getStepTag() + "[QueryForVpnConfiguration.init] ";
		
		// Get custom step properties.
		logger.info(LOGTAG + "Getting custom step properties...");
		
		// Access key
		String accessKeyId = getProperties().getProperty("accessKeyId", null);
		setAccessKeyId(accessKeyId);
		logger.info(LOGTAG + "accessKeyId is: " + getAccessKeyId());
		
		// Secret key
		String secretKey = getProperties().getProperty("secretKey", null);
		setSecretKey(secretKey);
		logger.info(LOGTAG + "secretKey is: present");
		
		// Set the roleArnPattern property
		setRoleArnPattern(getProperties().getProperty("roleArnPattern", null));
		logger.info(LOGTAG + "roleArnPattern property is: " + getRoleArnPattern());
		
		// Set the roleAssumptionDurationSeconds property
		setRoleAssumptionDurationSeconds(getProperties()
			.getProperty("roleAssumptionDurationSeconds", null));
		logger.info(LOGTAG + "roleAssumptionDurationSeconds is: " +
			getRoleAssumptionDurationSeconds());
	
		
		logger.info(LOGTAG + "Initialization complete.");
	}
	
	protected List<Property> run() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + "[QueryForVpnConfiguration.run] ";
		logger.info(LOGTAG + "Begin running the step.");

		// Return properties
		addResultProperty("stepExecutionMethod", RUN_EXEC_TYPE);

		// check if a VPC is being created
		String createVpc = getStepPropertyValue("CREATE_VPC_TYPE1_CFN_STACK", "createVpc");
		logger.info(LOGTAG + "createVpc=" + createVpc);
		if(Boolean.valueOf(createVpc)) {
			boolean allocatedNewAccount = false;
			String newAccountId = null;

			// Get some properties from previous steps.
			String vpn1ConnectionId =
					getStepPropertyValue("CREATE_VPC_TYPE1_CFN_STACK", "Vpn1ConnectionId");
			addResultProperty("Vpn1ConnectionId", vpn1ConnectionId);
			String vpn1InsideTunnelCidr1 =
					getStepPropertyValue("CREATE_VPC_TYPE1_CFN_STACK", "vpn1InsideTunnelCidr1");
			addResultProperty("vpn1InsideTunnelCidr1", vpn1InsideTunnelCidr1);
			String vpn2ConnectionId =
					getStepPropertyValue("CREATE_VPC_TYPE1_CFN_STACK", "Vpn2ConnectionId");
			addResultProperty("Vpn2ConnectionId", vpn2ConnectionId);
			String vpn2InsideTunnelCidr1 =
					getStepPropertyValue("CREATE_VPC_TYPE1_CFN_STACK", "vpn2InsideTunnelCidr1");
			addResultProperty("vpn2InsideTunnelCidr1", vpn2InsideTunnelCidr1);
			String accountId =
					getStepPropertyValue("CREATE_VPC_TYPE1_CFN_STACK", "accountId");
			addResultProperty("accountId", accountId);
			String region =
					getStepPropertyValue("CREATE_VPC_TYPE1_CFN_STACK", "region");
			addResultProperty("region", region);

			// Build the EC2 client.
			AmazonEC2Client client = buildAmazonEC2Client(accountId, region);
			setAmazonEC2Client(client);

			// Get the customer gateway configurations for the VPN connections
			String vpn1CustomerGatewayConfig = getCustomerGatewayConfig(vpn1ConnectionId);
			logger.info(LOGTAG + "vpn1CustomerGatewayConfig is: " + vpn1CustomerGatewayConfig);

			String vpn2CustomerGatewayConfig = getCustomerGatewayConfig(vpn2ConnectionId);
			logger.info(LOGTAG + "vpn2CustomerGatewayConfig is: " + vpn2CustomerGatewayConfig);

			// Get the remote ip address for the VPN connections
			String vpn1RemoteIpAddress = getRemoteIpAddress(vpn1CustomerGatewayConfig, vpn1InsideTunnelCidr1);
			logger.info(LOGTAG + "vpn1RemoteIpAddress is: " + vpn1RemoteIpAddress);
			addResultProperty("vpn1RemoteIpAddress", vpn1RemoteIpAddress);

			String vpn2RemoteIpAddress = getRemoteIpAddress(vpn2CustomerGatewayConfig, vpn2InsideTunnelCidr1);
			logger.info(LOGTAG + "vpn2RemoteIpAddress is: " + vpn2RemoteIpAddress);
			addResultProperty("vpn2RemoteIpAddress", vpn2RemoteIpAddress);

			// Get the preshared key for the VPN connections
			String vpn1PresharedKey = getPresharedKey(vpn1CustomerGatewayConfig, vpn1InsideTunnelCidr1);
			logger.info(LOGTAG + "vpn1PresharedKey is: " + vpn1PresharedKey);
			addResultProperty("vpn1PresharedKey", vpn1PresharedKey);

			String vpn2PresharedKey = getPresharedKey(vpn2CustomerGatewayConfig, vpn2InsideTunnelCidr1);
			logger.info(LOGTAG + "vpn2PresharedKey is: " + vpn2PresharedKey);
			addResultProperty("vpn2PresharedKey", vpn2PresharedKey);
		} else {
			logger.info(LOGTAG + "Bypass VPN configuration: not creating VPC");
		}
		
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
			"[QueryForVpnConfiguration.simulate] ";
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
			"[QueryForVpnConfiguration.fail] ";
		logger.info(LOGTAG + "Begin step failure simulation.");
		
		// Set return properties.
    	addResultProperty("stepExecutionMethod", FAILURE_EXEC_TYPE);
		
		// Update the step.
    	update(COMPLETED_STATUS, FAILURE_RESULT);
    	
    	// Log completion time.
    	long time = System.currentTimeMillis() - startTime;
    	logger.info(LOGTAG + "Step failure simulation completed in " + time + "ms.");
    	
    	// Return the properties.
    	return getResultProperties();
	}
	
	public void rollback() throws StepException {
		
		super.rollback();
		
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + 
			"[QueryForVpnConfiguration.rollback] ";
		
		logger.info(LOGTAG + "Rollback called, nothing to rollback.");
		
		update(ROLLBACK_STATUS, SUCCESS_RESULT);
		
		// Log completion time.
    	long time = System.currentTimeMillis() - startTime;
    	logger.info(LOGTAG + "Rollback completed in " + time + "ms.");
	}
	
	private void setAmazonEC2Client(AmazonEC2Client client) {
		m_client = client;
	}
	
	private AmazonEC2Client getAmazonEC2Client() {
		return m_client;
	}
	
	private void setAccessKeyId (String accessKeyId) throws 
		StepException {
	
		if (accessKeyId == null) {
			String errMsg = "accessKeyId property is null. " +
				"Can't continue.";
			throw new StepException(errMsg);
		}
		
		m_accessKeyId = accessKeyId;
	}

	private String getAccessKeyId() {
		return m_accessKeyId;
	}
	
	private void setSecretKey (String secretKey) throws 
		StepException {

		if (secretKey == null) {
			String errMsg = "secretKey property is null. " +
				"Can't continue.";
			throw new StepException(errMsg);
		}
	
		m_secretKey = secretKey;
	}

	private String getSecretKey() {
		return m_secretKey;
	}
	
	/**
	 * @param String, the pattern of the role to assume
	 * <P>
	 * This method sets the pattern of the role to assume
	 */
	private void setRoleArnPattern(String pattern) throws StepException {
		
		if (pattern == null) {
			String errMsg = "roleArnPattern property is null. " +
				"Can't assume role in target accounts. Can't continue.";
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg);
		}
		
		m_roleArnPattern = pattern;
	}

	/**
	 * @return String, the pattern of the role to assume
	 * <P>
	 * This method returns the pattern of the role to assume
	 */
	private String getRoleArnPattern() {
		return m_roleArnPattern;
	}
	
	/**
	 * @param String, the role assumption duration
	 * <P>
	 * This method sets the role assumption duration
	 */
	private void setRoleAssumptionDurationSeconds(String seconds) throws StepException {
		
		if (seconds == null) {
			String errMsg = "roleAssumptionDurationSeconds property is null. " +
				"Can't continue.";
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg);
		}
		
		m_roleAssumptionDurationSeconds = Integer.parseInt(seconds);
	}

	
	/**
	 * @return int, the role assumption duration
	 * <P>
	 * This method returns the role assumption duration in seconds
	 */
	private int getRoleAssumptionDurationSeconds() {
		return m_roleAssumptionDurationSeconds;
	}			
	
	private String getCustomerGatewayConfig(String vpnId) throws StepException {
		String LOGTAG = getStepTag() + "[getCustomerGatewayConfig] ";
		
		// Build the request.
		DescribeVpnConnectionsRequest request = new DescribeVpnConnectionsRequest();
		List vpnConnectionIds = new ArrayList<String>();
		vpnConnectionIds.add(vpnId);
		request.setVpnConnectionIds(vpnConnectionIds);
		
		// Send the request.
		DescribeVpnConnectionsResult result = null;
		try {
			logger.info(LOGTAG + "Sending the describe VPN connections request...");
			long queryStartTime = System.currentTimeMillis();
			result = getAmazonEC2Client().describeVpnConnections(request);
			long queryTime = System.currentTimeMillis() - queryStartTime;
			logger.info(LOGTAG + "received response to describe VPN connections " +
				"request in queryTime ms.");
		}
		catch (Exception e) {
			String errMsg = "An error occurred describing VPN connections. " +
				"The exception is: " + e.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg, e);
		}
		
		List<VpnConnection> vpnConnections = result.getVpnConnections();
		if (vpnConnections.size() == 1) {
			VpnConnection vpn = (VpnConnection)vpnConnections.get(0);
			return vpn.getCustomerGatewayConfiguration();
			
		}
		else {
			String errMsg = "Unexpected number of VpnConnections. " +
				"Found " + vpnConnections.size() + " expected 1.";
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg);
		}
		
	}
	
	   /**
     * 
     * @param String, accountId
     * @param String, region
     * <P>
     * @return, Amazon EC2 client connected to the correct
     * account with the correct role
     * 
     */
    private AmazonEC2Client buildAmazonEC2Client(String accountId, String region) {
    	String LOGTAG = getStepTag() + "[buildAmazonEC2Client] ";
    	
    	// Build the roleArn of the role to assume from the base ARN and 
        // the account number in the query spec.
        logger.info(LOGTAG + "The account targeted by this request is: " + accountId);
        logger.info(LOGTAG + "The region targeted by this request is: " + region);
        logger.info(LOGTAG + "The roleArnPattern is: " + getRoleArnPattern());
        String roleArn = getRoleArnPattern().replace("ACCOUNT_NUMBER", accountId);
        logger.info(LOGTAG + "Role ARN to assume for this request is: " + roleArn); 
        		
		// Instantiate a basic credential provider
        BasicAWSCredentials creds = new BasicAWSCredentials(getAccessKeyId(), getSecretKey());
        AWSStaticCredentialsProvider cp = new AWSStaticCredentialsProvider(creds);      
        
        // Create the STS client
        AWSSecurityTokenService sts = AWSSecurityTokenServiceClientBuilder.standard()
        								.withCredentials(cp)
        								.withRegion(region)
        								.build();       
        
        // Assume the appropriate role in the appropriate account.
        AssumeRoleRequest assumeRequest = new AssumeRoleRequest().withRoleArn(roleArn)
        	.withDurationSeconds(getRoleAssumptionDurationSeconds())
        	.withRoleSessionName("AwsAccountService");

        AssumeRoleResult assumeResult = sts.assumeRole(assumeRequest);
        Credentials credentials = assumeResult.getCredentials();

        // Instantiate a credential provider
        BasicSessionCredentials temporaryCredentials = new BasicSessionCredentials(credentials.getAccessKeyId(), credentials.getSecretAccessKey(), credentials.getSessionToken());
        AWSStaticCredentialsProvider credProvider = new AWSStaticCredentialsProvider(temporaryCredentials);
        
        // Create the EC2 client
        AmazonEC2Client ec2c = 
        	(AmazonEC2Client)AmazonEC2ClientBuilder
        	.standard().withCredentials(credProvider).withRegion(region).build();
    
        return ec2c;
    }
    
    private String getRemoteIpAddress(String customerGatewayConfig, 	
    	String insideIpCidr) throws StepException {
    	String LOGTAG = getStepTag() + "[getRemoteIpAddress] ";
    	
    	String remoteIpAddress = null;
   
    	SAXBuilder sb = new SAXBuilder();
    	
    	Document cgcDoc = null;
    	try {
    		cgcDoc = sb.build(new StringReader(customerGatewayConfig));
    	
    	}
    	catch (IOException ioe) {
    		String errMsg = "An error occured building and XML document " +
    			"from the customer gateway configuration string. The " +
    			"exception is: " + ioe.getMessage();
    		logger.error(LOGTAG + errMsg);
    		throw new StepException(errMsg, ioe);
     	}
    	catch (JDOMException je) {
    		String errMsg = "An error occured building and XML document " +
    			"from the customer gateway configuration string. The " +
    			"exception is: " + je.getMessage();
    		logger.error(LOGTAG + errMsg);
    		throw new StepException(errMsg, je);
     	}
    	
    	Element rootElement = cgcDoc.getRootElement();
    	
    	List tunnels = rootElement.getChildren("ipsec_tunnel");
    	ListIterator li = tunnels.listIterator();
    	while (li.hasNext()) {
    		Element e = (Element)li.next();
    		if (isMatchingTunnel(e, insideIpCidr)) {
    			logger.info(LOGTAG + "This is the matching tunnel.");
    			remoteIpAddress = e.getChild("vpn_gateway")
    				.getChild("tunnel_outside_address")
    				.getChildText("ip_address");
    			logger.info(LOGTAG + "remoteIpAddress is: " 
    				+ remoteIpAddress);
    		}
    		else {
    			logger.info(LOGTAG + "This is not the matching tunnel.");
    		}
    	}
    		
    	if (remoteIpAddress == null) {
    		String errMsg = "remoteIpAddress is null. Can't continue.";
    		logger.error(LOGTAG + errMsg);
    		throw new StepException(errMsg);
    	}
    	
    	return remoteIpAddress;
    	
    }
    
    private String getPresharedKey(String customerGatewayConfig, 	
    	String insideIpCidr) throws StepException {
    	String LOGTAG = getStepTag() + "[getPresharedKey] ";
    	
    	String presharedKey = null;
   
    	SAXBuilder sb = new SAXBuilder();
    	
    	Document cgcDoc = null;
    	try {
    		cgcDoc = sb.build(new StringReader(customerGatewayConfig));
    	
    	}
    	catch (IOException ioe) {
    		String errMsg = "An error occured building and XML document " +
    			"from the customer gateway configuration string. The " +
    			"exception is: " + ioe.getMessage();
    		logger.error(LOGTAG + errMsg);
    		throw new StepException(errMsg, ioe);
     	}
    	catch (JDOMException je) {
    		String errMsg = "An error occured building and XML document " +
    			"from the customer gateway configuration string. The " +
    			"exception is: " + je.getMessage();
    		logger.error(LOGTAG + errMsg);
    		throw new StepException(errMsg, je);
     	}
    	
    	Element rootElement = cgcDoc.getRootElement();
    	
    	List tunnels = rootElement.getChildren("ipsec_tunnel");
    	ListIterator li = tunnels.listIterator();
    	while (li.hasNext()) {
    		Element e = (Element)li.next();
    		if (isMatchingTunnel(e, insideIpCidr)) {
    			logger.info(LOGTAG + "This is the matching tunnel.");
    			presharedKey = e.getChild("ike")
    				.getChildText("pre_shared_key");
    			logger.info(LOGTAG + "presharedKey is: " 
    				+ presharedKey);
    		}
    		else {
    			logger.info(LOGTAG + "This is not the matching tunnel.");
    		}
    	}
    		
    	if (presharedKey == null) {
    		String errMsg = "presharedKey is null. Can't continue.";
    		logger.error(LOGTAG + errMsg);
    		throw new StepException(errMsg);
    	}
    	
    	return presharedKey;
        	
    }
	
    private boolean isMatchingTunnel(Element e, String insideIpCidr) 
    	throws StepException {
    	String LOGTAG = getStepTag() + "[isMatchingTunnel] ";
   	
    	String ipAddress = e.getChild("vpn_gateway")
    		.getChild("tunnel_inside_address")
    		.getChildText("ip_address");
    	
    	logger.info(LOGTAG + "ipAddress is: " + ipAddress + 
    		" insideIpCidr is: " + insideIpCidr);
    	
    	SubnetInfo subnet = (new SubnetUtils(insideIpCidr)).getInfo();
    	boolean isMatchingTunnel = subnet.isInRange(ipAddress);
    	logger.info(LOGTAG + "isMatchingTunnel: " + isMatchingTunnel);
    	
    	return isMatchingTunnel;
    	
    }
}
