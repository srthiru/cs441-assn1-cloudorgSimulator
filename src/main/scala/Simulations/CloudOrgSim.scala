package Simulations

import org.cloudbus.cloudsim.allocationpolicies.{VmAllocationPolicyAbstract, VmAllocationPolicyBestFit, VmAllocationPolicyFirstFit, VmAllocationPolicyRandom, VmAllocationPolicyRoundRobin, VmAllocationPolicySimple}
import org.cloudbus.cloudsim.brokers.{DatacenterBrokerAbstract, DatacenterBrokerBestFit, DatacenterBrokerFirstFit, DatacenterBrokerSimple}
import org.cloudbus.cloudsim.cloudlets.{Cloudlet, CloudletSimple}
import org.cloudbus.cloudsim.core.CloudSim
import org.cloudbus.cloudsim.datacenters.{Datacenter, DatacenterSimple}
import org.cloudbus.cloudsim.distributions.{ContinuousDistribution, StatisticalDistribution}
import org.cloudbus.cloudsim.hosts.{Host, HostSimple}
import org.cloudbus.cloudsim.provisioners.{PeProvisionerSimple, ResourceProvisionerSimple}
import org.cloudbus.cloudsim.resources.{Pe, PeSimple}
import org.cloudbus.cloudsim.schedulers.cloudlet.{CloudletSchedulerAbstract, CloudletSchedulerCompletelyFair, CloudletSchedulerSpaceShared, CloudletSchedulerTimeShared}
import org.cloudbus.cloudsim.schedulers.vm.{VmSchedulerAbstract, VmSchedulerSpaceShared, VmSchedulerTimeShared}
import org.cloudbus.cloudsim.utilizationmodels.{UtilizationModel, UtilizationModelDynamic, UtilizationModelFull, UtilizationModelStochastic}
import org.cloudbus.cloudsim.vms.{Vm, VmCost, VmSimple}
import org.cloudsimplus.builders.tables.{CloudletsTableBuilder, Table, TableBuilderAbstract, TableColumn, TextTableColumn}
import org.cloudsimplus.autoscaling.{HorizontalVmScaling, HorizontalVmScalingSimple}
import com.typesafe.config.{Config, ConfigFactory}

import java.text.NumberFormat
import java.util.Locale
import collection.JavaConverters.*
import HelperUtils.{CreateLogger, ObtainConfigReference, VmCreator}


class CloudOrgSim

object CloudOrgSim:

  // Creating logger instance
  val logger = CreateLogger(classOf[CloudOrgSim])
  val cloudsim = new CloudSim(0.05)
  val currencyFormat: NumberFormat = NumberFormat.getCurrencyInstance(Locale.US);

  def runSim(simName: String, configName: String = "application.conf") =

    // Get Config reference for the simulation
    val simConf = ConfigFactory.load(configName).getConfig("cloudSimulator."+simName)
    logger.info("Loaded config for " + simName)

    val dcConfig = simConf.getConfig("datacenters")
    val dataCenters = (1 to dcConfig.getInt("nDcs")).map(i => createDatacenter(dcConfig.getConfig("datacenter" + i), cloudsim))

    val nBrokers = simConf.getInt("brokers.nBrokers")
    val brokers = (1 to nBrokers).map(i => createBroker(simConf.getConfig("brokers.broker" + i)))

    val cloudletConf = simConf.getConfig("cloudlet")
    val cloudletList = (1 to simConf.getInt("nCloudlets")).map( i => createCloudlet(i, cloudletConf))

    val vmConf = simConf.getConfig("vm")
    val vmCreator = new VmCreator(vmConf)
    val vmList = (1 to simConf.getInt("nVms")).map (i => vmCreator.createScalableVm())

    brokers(0).submitVmList(vmList.asJava)
    brokers(0).submitCloudletList(cloudletList.asJava)

//    (0 to nBrokers-1).map(i => brokers(i).submitVmList(vmList.asJava))
//    (0 to nBrokers-1).map(i => brokers(i).submitCloudletList(cloudletList.asJava))

    logger.info("Starting cloud simulation...")
    cloudsim.start()

    val finishedCloudlets = (0 to nBrokers-1).map(i => brokers(i).getCloudletFinishedList().asScala).flatten

    val cloudletTable = new CloudletsTableBuilder(finishedCloudlets.asJava)
                            .addColumn(new TextTableColumn("Actual CPU Time", "Usage"), cloudlet => "%.2f".format(cloudlet.getActualCpuTime))
                            .addColumn(new TextTableColumn("CloudletCost", "CPU"), cloudlet => "$ %.2f".format(cloudlet.getActualCpuTime  * cloudlet.getCostPerSec))
                            .addColumn(new TextTableColumn("CloudletCost", "BW"), cloudlet => "$ %.2f".format(cloudlet.getAccumulatedBwCost))
                            .addColumn(new TextTableColumn("CloudletCost", "Total"), cloudlet => "$ %.2f".format(cloudlet.getTotalCost))
                            .addColumn(new TextTableColumn("Utilization %", "CPU"), cloudlet => "%.2f%%".format(cloudlet.getUtilizationOfCpu*100))
                            .addColumn(new TextTableColumn("Utilization %", "RAM"), cloudlet => "%.2f%%".format(cloudlet.getUtilizationOfRam*100))
                            .addColumn(new TextTableColumn("Utilization %", "BW"), cloudlet => "%.2f%%".format(cloudlet.getUtilizationOfBw*100))
                            .addColumn(new TextTableColumn("Utilization %", "BW"), cloudlet => "%.2f%%".format(cloudlet.getUtilizationOfBw*100))

    cloudletTable.build()

    val cloudletTotalCost = cloudletList.map(i => i.getTotalCost()).sum
    logger.info("Total Cloudlet Cost: " + currencyFormat.format(cloudletTotalCost))
    logger.info("Avg. Cost per Cloudlet: " + currencyFormat.format(cloudletTotalCost/cloudletList.length))

    showVmCosts(vmList)

    logger.info("Overall time taken for the execution of the cloudlets is: " + cloudsim.clockStr() + " secs")

  def createBroker(brokerConf: Config): DatacenterBrokerAbstract =
    val brokerType = brokerConf.getString("type")
    val broker = if brokerType == "firstfit" then new DatacenterBrokerFirstFit(cloudsim) else if brokerType == "bestfit" then new DatacenterBrokerBestFit(cloudsim) else new DatacenterBrokerSimple(cloudsim)

    val brokerMatchesTimezone = if brokerConf.hasPath("matchTimezone") then brokerConf.getString("matchTimezone") else "no"
    if brokerMatchesTimezone == "yes" then broker.setSelectClosestDatacenter(true) else broker.setSelectClosestDatacenter(false)

    broker

  def createDatacenter(dcConfig: Config, cloudsim: CloudSim): Datacenter =
    val nHosts = dcConfig.getInt("nHosts")
    logger.info("Data center config: " + dcConfig)
    val hostList = (1 to dcConfig.getInt("nHosts")).map (i => createHosts(dcConfig.getConfig("host")))
    val dc = new DatacenterSimple(cloudsim, hostList.toList.asJava)

    dc.setName(dcConfig.getString("name"))
    dc.setTimeZone(dcConfig.getDouble("timezone"))

    val vmAllocationPolicy = if dcConfig.hasPath("vmAllocationPolicy") then getVmAllocationPolicy(dcConfig.getString("vmAllocationPolicy")) else new VmAllocationPolicySimple()
    dc.setVmAllocationPolicy(vmAllocationPolicy)

    dc.getCharacteristics()
      .setCostPerSecond(dcConfig.getDouble("chars.cpuCost"))
      .setCostPerMem(dcConfig.getDouble("chars.ramCost"))
      .setCostPerStorage(dcConfig.getDouble("chars.storCost"))
      .setCostPerBw(dcConfig.getDouble("chars.bwCost"))

    dc.setSchedulingInterval(60)

    dc

  def createHosts(hostConfig: Config): Host =

    val peMips = hostConfig.getInt("mipsCapacity")/hostConfig.getInt("nPEs")
    val peList = (1 to hostConfig.getInt("nPEs")).map (i => new PeSimple(peMips, new PeProvisionerSimple()))

    val ram:Long = hostConfig.getInt("RAMInMBs")
    val bw:Long = hostConfig.getInt("BandwidthInMBps")
    val storage:Long = hostConfig.getInt("StorageInMBs")
    val ramProvisioner = new ResourceProvisionerSimple()
    val bwProvisioner = new ResourceProvisionerSimple()
    val vmScheduler = if hostConfig.hasPath("vmScheduler") then getVmScheduler(hostConfig.getString("vmScheduler")) else new VmSchedulerSpaceShared()

    val host = new HostSimple(ram, bw, storage, peList.asJava)

    host
      .setRamProvisioner(ramProvisioner)
      .setBwProvisioner(bwProvisioner)
      .setVmScheduler(vmScheduler)

    host

  def createCloudlet(i: Int, cloudletConf: Config): Cloudlet =

    val utilizationModel = if cloudletConf.hasPath("cloudletUtilModel") then getUtilModel(cloudletConf.getString("cloudletUtilModel")) else new UtilizationModelFull()

    new CloudletSimple(i, cloudletConf.getInt("length"), cloudletConf.getInt("PEs"))
      .setSizes(cloudletConf.getInt("sizes"))
      .setUtilizationModel(utilizationModel.asInstanceOf[UtilizationModel])

  def getUtilModel(utilModel: String): UtilizationModel ={
     if utilModel == "stochastic" then new UtilizationModelStochastic()
        else if utilModel == "dynamic" then new UtilizationModelDynamic().setUtilizationUpdateFunction(um => um.getUtilization() + um.getTimeSpan()*0.1)
        else UtilizationModelFull()
  }

  def getCloudletScheduler(cloudletScheduler: String): CloudletSchedulerAbstract = {
    if cloudletScheduler == "time" then new CloudletSchedulerTimeShared() else if cloudletScheduler == "space" then new CloudletSchedulerSpaceShared() else new CloudletSchedulerCompletelyFair()
  }

  def getVmScheduler(vmScheduler: String): VmSchedulerAbstract = {
    if vmScheduler == "time" then new VmSchedulerTimeShared() else new VmSchedulerSpaceShared()
  }

  def getVmAllocationPolicy(vmAllocationPolicy: String): VmAllocationPolicyAbstract = {
    return (if vmAllocationPolicy == "round" then new VmAllocationPolicyRoundRobin()
            else if vmAllocationPolicy == "best" then new VmAllocationPolicyBestFit()
            else if vmAllocationPolicy == "first" then new VmAllocationPolicyFirstFit()
            else new VmAllocationPolicySimple())
  }

  def showVmCosts(vmList: IndexedSeq[Vm]): Unit =
    (0 to vmList.length-1).foreach(i => logger.info("Processing Cost of VM" + i + ": "+ {
      val vmCost = new VmCost(vmList(i))
      currencyFormat.format(vmCost.getProcessingCost())
    }))

    (0 to vmList.length-1).foreach(i => logger.info("Memory Cost of VM" + i + ": "+ {
      val vmCost = new VmCost(vmList(i))
      currencyFormat.format(vmCost.getMemoryCost())
    }))

    (0 to vmList.length-1).foreach(i => logger.info("Bandwidth Cost of VM" + i + ": "+ {
      val vmCost = new VmCost(vmList(i))
      currencyFormat.format(vmCost.getBwCost())
    }))

    (0 to vmList.length-1).foreach(i => logger.info("Bandwidth Cost of Storage" + i + ": "+ {
      val vmCost = new VmCost(vmList(i))
      currencyFormat.format(vmCost.getStorageCost())
    }))

    (0 to vmList.length-1).foreach(i => logger.info("Cost of VM" + i + ": "+ {
      val vmCost = new VmCost(vmList(i))
      currencyFormat.format(vmCost.getTotalCost())
    }))












