package HelperUtils;

import Simulations.CloudOrgSim$;
import com.typesafe.config.Config;
import org.cloudbus.cloudsim.brokers.DatacenterBroker;
import org.cloudbus.cloudsim.schedulers.cloudlet.CloudletSchedulerAbstract;
import org.cloudbus.cloudsim.schedulers.cloudlet.CloudletSchedulerCompletelyFair;
import org.cloudbus.cloudsim.schedulers.cloudlet.CloudletSchedulerSpaceShared;
import org.cloudbus.cloudsim.schedulers.cloudlet.CloudletSchedulerTimeShared;
import org.cloudbus.cloudsim.vms.Vm;
import org.cloudbus.cloudsim.vms.VmSimple;
import org.cloudsimplus.autoscaling.HorizontalVmScaling;
import org.cloudsimplus.autoscaling.HorizontalVmScalingSimple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Predicate;

/**
 * <p>
 * An extension of {@link HorizontalVmScaling} implementation from the Cloudsimplus example
 * that allows defining the condition to identify an overloaded VM, based on any desired criteria, such as
 * current RAM, CPU and/or Bandwidth utilization.
 * A {@link DatacenterBroker} monitors the VMs that have
 * an HorizontalVmScaling object in order to create or destroy VMs on demand.
 * </p>
 *
 * <br>
 * <p>The overload condition has to be defined
 * by providing a {@link Predicate} using the {@link #setOverloadPredicate(Predicate)} method.
 * Check the {@link HorizontalVmScaling} documentation for details on how to enable horizontal down scaling
 * using the {@link DatacenterBroker}.
 * </p>
 *
 * @author Manoel Campos da Silva Filho
 * @since CloudSim Plus 1.0
 * @see HorizontalVmScaling
 */
public class VmCreator {

    // Creating logger instance
    private final Logger logger = LoggerFactory.getLogger(CloudOrgSim$.class);
    private int vms;
    private Config vmConf;

    public VmCreator(Config vmConf){
        this.vmConf = vmConf;
        this.vms = 0;
    }

    /**
     * Creates a Scalable Vm object that is able to scale horizontally when overloaded
     *
     * @return the created Vm
     */
    public Vm createScalableVm(){
        final Vm vm =  createVm();

        if(vmConf.hasPath("scalingEnabled") && vmConf.getString("scalingEnabled") == "yes"){
            final String scalingType = vmConf.hasPath("scalingType")? vmConf.getString("scalingType"): "horizontal";

            if(scalingType == "horizontal"){
                logger.info("Enabling horizontal scaling for VM "+ vm.getId());
                createHorizontalVmScaling(vm);
            }
            else{
                logger.warn("Scaling type not implemented, defaulting to horizontal scaling");
                logger.info("Enabling horizontal scaling for VM "+ vm.getId());
                createHorizontalVmScaling(vm);
            }
        }
        return vm;
    }

    /**
     * Creates a Vm object.
     *
     * @return the created Vm
     */
    private Vm createVm() {
        // ID for VM
        final int id = vms++;

        // Create new VM according to config parameters
        final CloudletSchedulerAbstract scheduler = vmConf.hasPath("cloudletScheduler")? getCloudletScheduler(vmConf.getString("cloudletScheduler")) : new CloudletSchedulerSpaceShared();

        final Vm vm =  new VmSimple(id, vmConf.getInt("mipsCapacity"), vmConf.getInt("PEs"))
                            .setRam(vmConf.getInt("RAMInMBs")).setBw(vmConf.getInt("BandwidthInMBps")).setSize(vmConf.getInt("StorageInMBs"))
                            .setCloudletScheduler(scheduler).setTimeZone(vmConf.getDouble("timezone"));

        logger.info("Created VM " + vm.getId() + " in datacenter " + vm.getHost().getDatacenter().getName());

        return vm;
    }

    private CloudletSchedulerAbstract getCloudletScheduler(String scheduler){
        switch(scheduler){
            case "time":
                return new CloudletSchedulerTimeShared();
            case "space":
                return new CloudletSchedulerSpaceShared();
            default:
                return new CloudletSchedulerCompletelyFair();
        }
    }

    /**
     * A {@link Predicate} that checks if a given VM is overloaded or not,
     * based on upper CPU utilization threshold.
     * A reference to this method is assigned to each {@link HorizontalVmScaling} created.
     *
     * @param vm the VM to check if it is overloaded
     * @return true if the VM is overloaded, false otherwise
     * @see #createHorizontalVmScaling(Vm)
     */
    private boolean isVmCpuOverloaded(final Vm vm) {
        // Check predicate if VM CPU utilization is more than 70%
        return vm.getCpuPercentUtilization() > 0.7;
    }

    private boolean isVmRamOverloaded(final Vm vm) {
        // Check predicate if VM RAM utilization is more than 70%
        return vm.getRam().getPercentUtilization() > 0.7;
    }

    /**
     * Creates a {@link HorizontalVmScaling} object for a given VM.
     *
     * @param vm the VM for which the Horizontal Scaling will be created
     * @see #createListOfScalableVms(int)
     */
    private void createHorizontalVmScaling(final Vm vm){
        // Adding horizontal scaling for VMs that will create a new VM if the criteria mentioned in the overload predicate is met
        final HorizontalVmScaling horizontalScaling = new HorizontalVmScalingSimple();

        horizontalScaling
                .setVmSupplier(this::createVm)
                .setOverloadPredicate(this::isVmRamOverloaded);
        vm.setHorizontalScaling(horizontalScaling);
    }

}
