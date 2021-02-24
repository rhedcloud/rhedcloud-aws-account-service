-- With the addition of Transit Gateway connectivity, the VPC can have different connection methods
-- alter the Virtual_Private_Cloud table to support them, knowing that all existing records are for VPN

alter table virtual_private_cloud
  rename column vpn_connection_profile_id to reference_id;
alter table virtual_private_cloud
  add column vpc_connection_method varchar(255);

update virtual_private_cloud set vpc_connection_method = 'VPN';
