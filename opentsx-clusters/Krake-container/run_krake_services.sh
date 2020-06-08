#####################################################
# Multiple files can be generated at the same time: #
#####################################################
#
# krake_generate_config /root/krake/config/*.template /root/krake/rok.yaml.template
#

python3 -m krake.api &

python3 -m krake.controller.gc &

python3 -m krake.controller.scheduler &

python3 -m krake.controller.kubernetes &

