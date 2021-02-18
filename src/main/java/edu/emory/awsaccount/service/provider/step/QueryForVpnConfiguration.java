/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2017 Emory University. All rights reserved.
 ******************************************************************************/
package edu.emory.awsaccount.service.provider.step;

import com.amazon.aws.moa.objects.resources.v1_0.Property;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.BasicSessionCredentials;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.DescribeVpnConnectionsRequest;
import com.amazonaws.services.ec2.model.DescribeVpnConnectionsResult;
import com.amazonaws.services.ec2.model.VpnConnection;
import com.amazonaws.services.securitytoken.AWSSecurityTokenService;
import com.amazonaws.services.securitytoken.AWSSecurityTokenServiceClientBuilder;
import com.amazonaws.services.securitytoken.model.AssumeRoleRequest;
import com.amazonaws.services.securitytoken.model.AssumeRoleResult;
import com.amazonaws.services.securitytoken.model.Credentials;
import edu.emory.awsaccount.service.provider.VirtualPrivateCloudProvisioningProvider;
import org.apache.commons.net.util.SubnetUtils;
import org.apache.commons.net.util.SubnetUtils.SubnetInfo;
import org.jdom.Document;
import org.jdom.Element;
import org.jdom.input.SAXBuilder;
import org.openeai.config.AppConfig;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Query AWS for the remoteIpAddresses and the prehsared keys for the site-to-site VPN connection.
 * <p>
 *
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 17 August 2018
 **/
public class QueryForVpnConfiguration extends AbstractStep implements Step {
    private static final String LOGTAG = "[QueryForVpnConfiguration] ";

    private String m_accessKeyId = null;
    private String m_secretKey = null;
    private String m_roleArnPattern = null;
    private int m_roleAssumptionDurationSeconds = 0;
    private AmazonEC2Client m_client = null;

    public void init(String provisioningId, Properties props,
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
        setRoleAssumptionDurationSeconds(getProperties().getProperty("roleAssumptionDurationSeconds", null));
        logger.info(LOGTAG + "roleAssumptionDurationSeconds is: " + getRoleAssumptionDurationSeconds());

        logger.info(LOGTAG + "Initialization complete.");
    }

    protected List<Property> run() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = getStepTag() + "[QueryForVpnConfiguration.run] ";
        logger.info(LOGTAG + "Begin running the step.");

        // Return properties
        addResultProperty("stepExecutionMethod", RUN_EXEC_TYPE);

        // check if a VPC is being created
        boolean createVpc = Boolean.parseBoolean(getStepPropertyValue("DETERMINE_VPC_TYPE", "createVpc"));
        String vpcConnectionMethod = getStepPropertyValue("DETERMINE_VPC_CONNECTION_METHOD", "vpcConnectionMethod");

        if (createVpc && vpcConnectionMethod.equals("VPN")) {
            addResultProperty("createVpnConnection", String.valueOf(true));

            // Get some properties from previous steps.
            String vpn1ConnectionId = getStepPropertyValue("CREATE_VPC_TYPE1_CFN_STACK", "Vpn1ConnectionId");
            String vpn1InsideTunnelCidr1 = getStepPropertyValue("CREATE_VPC_TYPE1_CFN_STACK", "vpn1InsideTunnelCidr1");
            String vpn2ConnectionId = getStepPropertyValue("CREATE_VPC_TYPE1_CFN_STACK", "Vpn2ConnectionId");
            String vpn2InsideTunnelCidr1 = getStepPropertyValue("CREATE_VPC_TYPE1_CFN_STACK", "vpn2InsideTunnelCidr1");
            String accountId = getStepPropertyValue("CREATE_VPC_TYPE1_CFN_STACK", "accountId");
            String region = getStepPropertyValue("CREATE_VPC_TYPE1_CFN_STACK", "region");

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
            logger.info(LOGTAG + "Bypass VPN configuration: not creating VPC or not VPN connectivity");
            addResultProperty("createVpnConnection", String.valueOf(false));
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
        String LOGTAG = getStepTag() + "[QueryForVpnConfiguration.simulate] ";
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
        String LOGTAG = getStepTag() + "[QueryForVpnConfiguration.fail] ";
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
        long startTime = System.currentTimeMillis();

        super.rollback();

        String LOGTAG = getStepTag() + "[QueryForVpnConfiguration.rollback] ";
        logger.info(LOGTAG + "Rollback called, but this step has nothing to roll back.");

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

    private void setAccessKeyId(String accessKeyId) throws StepException {
        if (accessKeyId == null) {
            String errMsg = "accessKeyId property is null. Can't continue.";
            throw new StepException(errMsg);
        }

        m_accessKeyId = accessKeyId;
    }

    private String getAccessKeyId() {
        return m_accessKeyId;
    }

    private void setSecretKey(String secretKey) throws StepException {
        if (secretKey == null) {
            String errMsg = "secretKey property is null. Can't continue.";
            throw new StepException(errMsg);
        }

        m_secretKey = secretKey;
    }

    private String getSecretKey() {
        return m_secretKey;
    }

    private void setRoleArnPattern(String pattern) throws StepException {
        if (pattern == null) {
            String errMsg = "roleArnPattern property is null. Can't assume role in target accounts. Can't continue.";
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg);
        }

        m_roleArnPattern = pattern;
    }

    private String getRoleArnPattern() {
        return m_roleArnPattern;
    }

    private void setRoleAssumptionDurationSeconds(String seconds) throws StepException {
        if (seconds == null) {
            String errMsg = "roleAssumptionDurationSeconds property is null. Can't continue.";
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg);
        }

        m_roleAssumptionDurationSeconds = Integer.parseInt(seconds);
    }

    private int getRoleAssumptionDurationSeconds() {
        return m_roleAssumptionDurationSeconds;
    }

    private String getCustomerGatewayConfig(String vpnId) throws StepException {
        String LOGTAG = getStepTag() + "[QueryForVpnConfiguration.getCustomerGatewayConfig] ";

        // Build the request.
        DescribeVpnConnectionsRequest request = new DescribeVpnConnectionsRequest();
        List<String> vpnConnectionIds = new ArrayList<>();
        vpnConnectionIds.add(vpnId);
        request.setVpnConnectionIds(vpnConnectionIds);

        // Send the request.
        DescribeVpnConnectionsResult result;
        try {
            logger.info(LOGTAG + "Sending the describe VPN connections request...");
            long queryStartTime = System.currentTimeMillis();
            result = getAmazonEC2Client().describeVpnConnections(request);
            long queryTime = System.currentTimeMillis() - queryStartTime;
            logger.info(LOGTAG + "received response to describe VPN connections request in " + queryTime + "ms.");
        } catch (Exception e) {
            String errMsg = "An error occurred describing VPN connections. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }

        List<VpnConnection> vpnConnections = result.getVpnConnections();
        if (vpnConnections.size() == 1) {
            VpnConnection vpn = vpnConnections.get(0);
            return vpn.getCustomerGatewayConfiguration();

        } else {
            String errMsg = "Unexpected number of VpnConnections. Found " + vpnConnections.size() + " expected 1.";
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg);
        }
    }

    /**
     * Amazon EC2 client connected to the correct account with the correct role
     */
    private AmazonEC2Client buildAmazonEC2Client(String accountId, String region) {
        String LOGTAG = getStepTag() + "[QueryForVpnConfiguration.buildAmazonEC2Client] ";

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
        return (AmazonEC2Client) AmazonEC2ClientBuilder.standard().withCredentials(credProvider).withRegion(region).build();
    }

    private String getRemoteIpAddress(String customerGatewayConfig, String insideIpCidr) throws StepException {
        String LOGTAG = getStepTag() + "[QueryForVpnConfiguration.getRemoteIpAddress] ";

        String remoteIpAddress = null;

        SAXBuilder sb = new SAXBuilder();

        Document cgcDoc;
        try {
            cgcDoc = sb.build(new StringReader(customerGatewayConfig));

        } catch (Exception ioe) {
            String errMsg = "An error occurred building and XML document " +
                    "from the customer gateway configuration string. The exception is: " + ioe.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, ioe);
        }

        Element rootElement = cgcDoc.getRootElement();

        @SuppressWarnings("unchecked")
        List<Element> tunnels = rootElement.getChildren("ipsec_tunnel");
        for (Element e : tunnels) {
            if (isMatchingTunnel(e, insideIpCidr)) {
                logger.info(LOGTAG + "This is the matching tunnel.");
                remoteIpAddress = e.getChild("vpn_gateway").getChild("tunnel_outside_address").getChildText("ip_address");
                logger.info(LOGTAG + "remoteIpAddress is: " + remoteIpAddress);
            } else {
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

    private String getPresharedKey(String customerGatewayConfig, String insideIpCidr) throws StepException {
        String LOGTAG = getStepTag() + "[QueryForVpnConfiguration.getPresharedKey] ";

        String presharedKey = null;

        SAXBuilder sb = new SAXBuilder();

        Document cgcDoc;
        try {
            cgcDoc = sb.build(new StringReader(customerGatewayConfig));

        } catch (Exception ioe) {
            String errMsg = "An error occurred building and XML document " +
                    "from the customer gateway configuration string. The exception is: " + ioe.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, ioe);
        }

        Element rootElement = cgcDoc.getRootElement();

        @SuppressWarnings("unchecked")
        List<Element> tunnels = rootElement.getChildren("ipsec_tunnel");
        for (Element e : tunnels) {
            if (isMatchingTunnel(e, insideIpCidr)) {
                logger.info(LOGTAG + "This is the matching tunnel.");
                presharedKey = e.getChild("ike").getChildText("pre_shared_key");logger.info(LOGTAG + "presharedKey is: " + presharedKey);
            } else {
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

    private boolean isMatchingTunnel(Element e, String insideIpCidr) {
        String LOGTAG = getStepTag() + "[QueryForVpnConfiguration.isMatchingTunnel] ";

        String ipAddress = e.getChild("vpn_gateway").getChild("tunnel_inside_address").getChildText("ip_address");

        logger.info(LOGTAG + "ipAddress is: " + ipAddress + " insideIpCidr is: " + insideIpCidr);

        SubnetInfo subnet = (new SubnetUtils(insideIpCidr)).getInfo();
        boolean isMatchingTunnel = subnet.isInRange(ipAddress);
        logger.info(LOGTAG + "isMatchingTunnel: " + isMatchingTunnel);

        return isMatchingTunnel;
    }
}
