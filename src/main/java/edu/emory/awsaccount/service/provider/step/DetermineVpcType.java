package edu.emory.awsaccount.service.provider.step;

import com.amazon.aws.moa.objects.resources.v1_0.Property;

import java.util.List;

public class DetermineVpcType extends AbstractStep implements Step {
  private static final String CREATE_VPC_PROPERTY = "createVpc";
  private static final String VPC_TYPE_PROPERTY = "vpcType";

  @Override
  protected List<Property> simulate() throws StepException {
    long startTime = System.currentTimeMillis();
    String LOGTAG = getStepTag() + "[DetermineVpcType.simulate] ";
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

  @Override
  protected List<Property> run() throws StepException {
    String LOGTAG = getStepTag() + "[DetermineVpcType.run] ";
    logger.info(LOGTAG + " Step started");

    // get the creating account flag
    String allocateNewAccount =
        getStepPropertyValue("DETERMINE_NEW_OR_EXISTING_ACCOUNT", "allocateNewAccount");
    // get the VPC type flag
    String vpcType =
        getVirtualPrivateCloudProvisioning().getVirtualPrivateCloudRequisition().getType();

    logger.info(LOGTAG + " creatingAccount=" + allocateNewAccount);
    logger.info(LOGTAG + " vpcType=" + vpcType);

    if (allocateNewAccount != null && vpcType != null) {
      // if we're not allocating anew account and the type is 0 then raise an exception
      if (vpcType.equals("0") && !Boolean.valueOf(allocateNewAccount)) {
        throw new StepException("Cannot use VPC type 0 when not allocating a new account");
      }

      // set the result properties for vpc type and create
      addResultProperty(VPC_TYPE_PROPERTY, vpcType);
      addResultProperty(CREATE_VPC_PROPERTY, String.valueOf(vpcType.equals("1")));
    }

    update(COMPLETED_STATUS, SUCCESS_RESULT);

    return getResultProperties();
  }

  @Override
  protected List<Property> fail() throws StepException {
    String LOGTAG = getStepTag() + "[DetermineVpcType.fail] ";
    logger.info(LOGTAG + "Begin step failure simulation.");

    // Set return properties.
    addResultProperty("stepExecutionMethod", FAILURE_EXEC_TYPE);

    // Update the step.
    update(COMPLETED_STATUS, FAILURE_RESULT);

    // Return the properties.
    return getResultProperties();
  }

  @Override
  public void rollback() throws StepException {
    String LOGTAG = getStepTag() + "[DetermineVpcType.rollback] ";
    super.rollback();

    logger.info(LOGTAG + "Rollback called, but this step has nothing to roll back.");
    update(ROLLBACK_STATUS, SUCCESS_RESULT);
  }
}
