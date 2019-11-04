# RHEDcloud AWS Account Service README

##Copyright

This software was created at Emory University. Please see NOTICE.txt for Emory's copyright and disclaimer notifications. This software was extended and modified by the RHEDcloud Foundation and is copyright 2019 by the RHEDcloud Foundation.

##License

This software is licensed under the Apache 2.0 license included in this distribution as LICENSE-2.0.txt

##Description

An AWS account and service metadata repository is necessary to provision and manage accounts, assess AWS services for risks, to scan accounts for security risks, and to integrate AWS consolidated billing with an organization's finance systems. The Emory AWS Account Service is a backend application exposed as a web service to perform these functions. Specifically, it implements the following high-level features:

1. Persists AWS Account Metadata such as account number, account, owner, compliance class, financial account number, e-mail addresses, etc.
2. Persists VPC metadata for the VPCs in accounts including their ID, type, CIDR range, VPN connection profile, etc.
3. Exposes the AWS account alias for accounts to a number of service components that need to display the account alias.
4. Persists account notifications for security risk detections and remediations as well as other administrative events. The service also creates a user notification for all users associated with the account for every account notification.
4. Persists user notifications to record delivery of notifications to specific users and also delivers notifications to the user via other modalities they have selected in their user profile (presently e-mail).
6. Exposes organization-specific logic for who is allowed to provision new accounts for other components of the solution that need to make this determination such as the provisioning orchestration and the ServiceNow request form.
7. Exposes a multi-step, transactional orchestration for automatically provisioning AWS accounts that are integrated into Emory's identity, network, and security infrastructure. Presently there are approximately 45 steps in total for provisioning that have been identified for accounts with type 1 and type 2 VPCs. Emory has successfully implemented the 30 step process for provisioning accounts with type 1 VPCs.
8. Picks up and processes the monthly master account bill, persisting the data for permanent storage and produces a file to import into Emory's PeopleSoft financial accounting system. Each account owner will be billed in the Emory financial system for all charges pertaining to their account by associating these charges with the financial account number they provided at the time they provisioned the account.
9. Detects and persists a master list of AWS services using the AWS support API and exposes service operations to maintain all metadata about these services that Emory must manage to determine whether a service is HIPAA eligible according to AWS and according to Emory, what the display name of the service is, if it is fully available, blocked, or available with countermeasures, etc.
10. Persists and exposes service operations for managing security risk assessments for each AWS service. Implementing specific, verifiable controls and tests in the form of IAM policies, services control policies, and security risk detectors and remediators requires a highly structured security risk assessment and enumeration of required controls.
11. Persists and exposes web service operations for terms of use to which all end users must agree at relevant points of service such as from the VPCP web app and TKI client.
12. Persists and exposes web service operations for terms of use agreements by end users.
13. Persists and exposes user profile data for the AWS account service
14. Exposes web service operations for a convenience object called AccountUser which aggregates user data from the Emory directory, authoritative person system, and identity management, so many components of the solution do not have to do that themselves.

In order to implement many of these features, the AWS Account Service must invoke the services of an authorization service, which at Emory is implemented by the Identity Management web service. This service provides Emory cannonical role and role assignment data, which tells the AWS Account Service which users are associated with which accounts and roles. Other implementing sites will need to implement or adapt a service like this to expose their account roles. Emory uses a product called NetIQ for this purpose and Emory's Identity Management web service exposes this data. Other implementing site may use a directory service like LDAP or Active Directory, use AWS principals, or some other commercial or open source identity management solution. Emory has started abstracting this out into a general process with the AccountUser object that could provide a common object for all implementing sites to provide all user and authorization data the service needs.

