/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2017 Emory University. All rights reserved. 
 ******************************************************************************/
package edu.emory.awsaccount.service.provider.step;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.apache.commons.net.util.SubnetUtils;
import org.apache.commons.net.util.SubnetUtils.SubnetInfo;
import org.openeai.config.AppConfig;
import com.amazon.aws.moa.objects.resources.v1_0.Property;
import com.amazon.aws.moa.objects.resources.v1_0.ProvisioningStep;

import edu.emory.awsaccount.service.provider.VirtualPrivateCloudProvisioningProvider;

/**
 * Send a VpnConnectionProfileAssignment.Generate-Request to the 
 * NetworkOpsService to reserve a VpnConnectionProfile for this
 * provisioning run.
 * <P>
 * 
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 2 September 2018
 **/
public class ComputeVpcSubnets extends AbstractStep implements Step {

	public void init (String provisioningId, Properties props, 
			AppConfig aConfig, VirtualPrivateCloudProvisioningProvider vpcpp) 
			throws StepException {
		
		super.init(provisioningId, props, aConfig, vpcpp);
		
		String LOGTAG = getStepTag() + "[ComputeVpcSubnets.init] ";
		
		
		logger.info(LOGTAG + "Initialization complete.");
		
	}
	
	protected List<Property> run() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + "[ComputeVpcSubnets.run] ";
		logger.info(LOGTAG + "Begin running the step.");
		
		String stepResult = FAILURE_RESULT;
		
		// Return properties
		addResultProperty("stepExecutionMethod", RUN_EXEC_TYPE);

		logger.info(LOGTAG + "Checking for applicable VPC network");
		String applicableVpcNetwork = getStepPropertyValue("DETERMINE_VPC_CIDR", "vpcNetwork");
		logger.info(LOGTAG + "applicableVpcNetwork=" + applicableVpcNetwork);
		if(applicableVpcNetwork.equals("not applicable")) {
			logger.info(LOGTAG + "VPC network is not applicable; skipping VPC subnet determination");
			addResultProperty("vpcNetwork", "no applicable");
			stepResult = SUCCESS_RESULT;
		} else {
			logger.info(LOGTAG + "Proceed with VPC subnet determination");
			// Get the vpcNetwork property from the
			// DETERMINE_VPC_CIDR step.
			logger.info(LOGTAG + "Getting properties from preceding steps...");
			ProvisioningStep step1 = getProvisioningStepByType("DETERMINE_VPC_CIDR");
			String vpcNetwork = null;
			if (step1 != null) {
				logger.info(LOGTAG + "Step DETERMINE_VPC_CIDR found.");
				vpcNetwork = getResultProperty(step1, "vpcNetwork");
				addResultProperty("vpcNetwork", vpcNetwork);
				logger.info(LOGTAG + "Property vpcNetwork from preceding " +
						"step is: " + vpcNetwork);
			} else {
				String errMsg = "Step DETERMINE_VPC_CIDR not found. " +
						"Can't continue.";
				logger.error(LOGTAG + errMsg);
				throw new StepException(errMsg);
			}

			// If the vpcNetwork is not null, compute the subnets for the VPC
			if (vpcNetwork != null) {


				// Begin pseudocode provided by Paul Petersen. Modified for proper syntax.
				String originalCidr = vpcNetwork;

				logger.info(LOGTAG + "originalCidr (vpcNetwork) is: " + originalCidr);

				String[] originalCidrArray = originalCidr.split("/");
				String originalCidrNetwork = originalCidrArray[0];
				String originalCidrBits = originalCidrArray[1];
				logger.info(LOGTAG + "originalCidrBits is: " + originalCidrBits);
				int bits = Integer.parseInt(originalCidrBits);

				String mgmtPubMask = Integer.toString(bits + 3);
				logger.info(LOGTAG + "mgmtPubMask is: " + mgmtPubMask);
				addResultProperty("mgmtPubMask", mgmtPubMask);

				String privMask = Integer.toString(bits + 2);
				logger.info(LOGTAG + "privMask is: " + privMask);
				addResultProperty("privMask", privMask);

				String mgmt1Subnet = originalCidrNetwork + "/" + mgmtPubMask;
				logger.info(LOGTAG + "mgmt1Subnet is: " + mgmt1Subnet);
				addResultProperty("mgmt1Subnet", mgmt1Subnet);

				String mgmt2Subnet = getNextSubnet(mgmt1Subnet, mgmtPubMask);
				logger.info(LOGTAG + "mgmt2Subnet is: " + mgmt2Subnet);
				addResultProperty("mgmt2Subnet", mgmt2Subnet);

				String public1Subnet = getNextSubnet(mgmt2Subnet, mgmtPubMask);
				logger.info(LOGTAG + "public1Subnet is: " + public1Subnet);
				addResultProperty("public1Subnet", public1Subnet);

				String public2Subnet = getNextSubnet(public1Subnet, mgmtPubMask);
				logger.info(LOGTAG + "public2Subnet is: " + public2Subnet);
				addResultProperty("public2Subnet", public2Subnet);

				String[] private1NetworkArray = getNextSubnet(public2Subnet, mgmtPubMask).split("/");
				logger.info(LOGTAG + "private1NetworkArray is: " + private1NetworkArray);
				String private1Network = private1NetworkArray[0];
				logger.info(LOGTAG + "private1Network is: " + private1Network);

				String private1Subnet = private1Network + "/" + privMask;
				logger.info(LOGTAG + "private1Subnet is: " + private1Subnet);
				addResultProperty("private1Subnet", private1Subnet);

				String private2Subnet = getNextSubnet(private1Subnet, privMask);
				logger.info(LOGTAG + "private2Subnet is: " + private2Subnet);
				addResultProperty("private2Subnet", private2Subnet);

				// End pseudocode provided by Paul Petersen.

				stepResult = SUCCESS_RESULT;
			}

			// Otherwise, if vpcNetwork is null the subnets cannot be computed.
			else {
				logger.info(LOGTAG + "vpcNetwork property is null. Cannot " +
						"compute subnets.");
				addResultProperty("vpcNetwork", "null");
			}
		}
		
		// Update the step.
		update(COMPLETED_STATUS, stepResult);
    	
    	// Log completion time.
    	long time = System.currentTimeMillis() - startTime;
    	logger.info(LOGTAG + "Step run completed in " + time + "ms.");
    	
    	// Return the properties.
    	return getResultProperties();
    	
	}
	
	protected List<Property> simulate() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + 
			"[ComputeVpcSubnets.simulate] ";
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
			"[ComputeVpcSubnets.fail] ";
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
			"[ComputeVpcSubnets.rollback] ";
		logger.info(LOGTAG + "Rollback called, nothing to roll back.");
		
		update(ROLLBACK_STATUS, SUCCESS_RESULT);
		
		// Log completion time.
    	long time = System.currentTimeMillis() - startTime;
    	logger.info(LOGTAG + "Rollback completed in " + time + "ms.");
	}
	
	// method provided by Paul Petersen.
	private static final String nextIpAddress(final String input) {
	    final String[] tokens = input.split("\\.");
	    if (tokens.length != 4)
	        throw new IllegalArgumentException();
	    for (int i = tokens.length - 1; i >= 0; i--) {
	        final int item = Integer.parseInt(tokens[i]);
	        if (item < 255) {
	            tokens[i] = String.valueOf(item + 1);
	            for (int j = i + 1; j < 4; j++) {
	                tokens[j] = "0";
	            }
	            break;
	        }
	    }
	    return new StringBuilder()
	    .append(tokens[0]).append('.')
	    .append(tokens[1]).append('.')
	    .append(tokens[2]).append('.')
	    .append(tokens[3])
	    .toString();
	}
	
	// method pseudocode provided by Paul Petersen
	private static final String getNextSubnet(String inputSubnet, String bits) {
	   SubnetUtils utils = new SubnetUtils(inputSubnet);
	   SubnetInfo info = utils.getInfo();
	   String bcastIpAddress = info.getBroadcastAddress();
	   String nextNetwork = nextIpAddress(bcastIpAddress);
	   String nextSubnet = nextNetwork + "/" + bits;
	   return(nextSubnet);
	}
	
	private String addToNetmask(String netmask, int i) {
		String LOGTAG = getStepTag() + "[ComputeVpcSubnets.addToNetMask] ";
		logger.info(LOGTAG + "netmask: " + netmask);
		String[] octets = netmask.split("\\.");
		logger.info(LOGTAG + "octets: " + octets[0] + " " + octets[1] +
				" " + octets[2] + " " + octets[3]);
		String lastOctet = octets[3];
		logger.info(LOGTAG + "lastOctet: " + lastOctet);
		int o = Integer.parseInt(lastOctet);
		o = o + i;
		lastOctet = Integer.toString(o);
		String newNetmask = octets[0] + "." + octets[1] +
			"." + octets[2] + "." + lastOctet;
		logger.info(LOGTAG + "newNetmask: " + newNetmask);
		return newNetmask;
	}
}
