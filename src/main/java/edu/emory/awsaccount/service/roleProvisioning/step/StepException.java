/* *****************************************************************************
 This file is part of the RHEDcloud AWS Account Service.

 Copyright 2020 RHEDcloud Foundation. All rights reserved.
 ******************************************************************************/

package edu.emory.awsaccount.service.roleProvisioning.step;

/**
  * An exception for provisioning step errors.
  */
public class StepException extends Exception {
  /**
   * Default constructor.
   */
  public StepException() {
    super();
  }
  /**
    * Message constructor.
    */
  public StepException(String msg) {
    super(msg);
  }
  /**
    * Throwable constructor.
    */
  public StepException(String msg, Throwable e) {
    super(msg, e);
  }
}