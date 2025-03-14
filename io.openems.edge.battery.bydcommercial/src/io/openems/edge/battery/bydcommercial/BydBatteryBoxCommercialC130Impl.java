package io.openems.edge.battery.bydcommercial;

import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventHandler;
import org.osgi.service.event.propertytypes.EventTopics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.channel.Unit;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.types.OpenemsType;
import io.openems.edge.battery.api.Battery;
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.ElementToChannelConverter;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.SignedWordElement;
import io.openems.edge.bridge.modbus.api.element.StringWordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.common.channel.IntegerWriteChannel;
import io.openems.edge.common.channel.StateChannel;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.event.EdgeEventConstants;
import io.openems.edge.common.startstop.StartStop;
import io.openems.edge.common.startstop.StartStoppable;
import io.openems.edge.common.taskmanager.Priority;
import org.osgi.service.metatype.annotations.Designate;
import io.openems.edge.common.channel.ChannelId;
import io.openems.edge.common.channel.value.Value;
import io.openems.edge.common.type.TypeUtils;

import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.DIRECT_1_TO_1;

@Designate(ocd = Config.class, factory = true)
@Component(
    name = "Byd.BatteryBox.Commercial.C130",
    immediate = true,
    configurationPolicy = ConfigurationPolicy.REQUIRE
)
@EventTopics({
    EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE
})
public class BydBatteryBoxCommercialC130Impl extends AbstractOpenemsModbusComponent
        implements BydBatteryBoxCommercialC130, Battery, ModbusComponent, OpenemsComponent, EventHandler {

    private static final int CLUSTER_BASE = 0x1100; // Cluster status
    private static final int CELL_BASE = 0x2000;    // Cell data base
    private static final int CELL_OFFSET = 0x20;    // 32 registers per module
    private static final int PRODUCT_BASE = 0x7050; // Product info

    private final Logger log = LoggerFactory.getLogger(BydBatteryBoxCommercialC130Impl.class);

    @Reference
    private ConfigurationAdmin cm;

    @Override
    @Reference(policy = ReferencePolicy.STATIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.MANDATORY)
    protected void setModbus(BridgeModbus modbus) {
        super.setModbus(modbus);
    }

    private Config config;

    public BydBatteryBoxCommercialC130Impl() {
        super(
            OpenemsComponent.ChannelId.values(),
            ModbusComponent.ChannelId.values(),
            Battery.ChannelId.values(),
            BydBatteryBoxCommercialC130.ChannelId.values()
        );
    }

    @Activate
    private void activate(ComponentContext context, Config config) throws OpenemsNamedException {
        this.config = config;
        if (super.activate(context, config.id(), config.alias(), config.enabled(), config.modbusUnitId(), this.cm,
                "Modbus", config.modbus_id())) {
            return;
        }
        this.logInfo(this.log, "Activated BMS with " + config.numberOfModules() + " modules, host: " + config.host() + ", port: " + config.port());
    }

    @Deactivate
    protected void deactivate() {
        super.deactivate();
        this.logInfo(this.log, "Deactivated BMS.");
    }

    @Override
    protected ModbusProtocol defineModbusProtocol() {
        ModbusProtocol protocol = new ModbusProtocol(this);

        // Cluster Status (0x1100, 45 registers)
        protocol.addTask(new FC3ReadRegistersTask(CLUSTER_BASE, Priority.HIGH,
            m(BydBatteryBoxCommercialC130.ChannelId.TOTAL_VOLTAGE, new UnsignedWordElement(CLUSTER_BASE), v -> v * 10), // 0.1V to mV
            m(BydBatteryBoxCommercialC130.ChannelId.CHARGE_DISCHARGE_STATE, new UnsignedWordElement(CLUSTER_BASE + 1), DIRECT_1_TO_1),
            m(BydBatteryBoxCommercialC130.ChannelId.BMS_SELFTEST_STATUS, new UnsignedWordElement(CLUSTER_BASE + 2), DIRECT_1_TO_1),
            m(BydBatteryBoxCommercialC130.ChannelId.TOTAL_CURRENT, new SignedWordElement(CLUSTER_BASE + 5), v -> toSigned16bit(v) * 100), // 0.1A to mA
            m(BydBatteryBoxCommercialC130.ChannelId.TOTAL_SOC, new UnsignedWordElement(CLUSTER_BASE + 7), DIRECT_1_TO_1),
            m(BydBatteryBoxCommercialC130.ChannelId.TOTAL_SOH, new UnsignedWordElement(CLUSTER_BASE + 8), DIRECT_1_TO_1),
            m(BydBatteryBoxCommercialC130.ChannelId.MAX_CELL_TEMPERATURE, new UnsignedWordElement(CLUSTER_BASE + 9), DIRECT_1_TO_1),
            m(BydBatteryBoxCommercialC130.ChannelId.MAX_TEMP_MODULE, new UnsignedWordElement(CLUSTER_BASE + 10), DIRECT_1_TO_1),
            m(BydBatteryBoxCommercialC130.ChannelId.MAX_TEMP_IN_MODULE, new UnsignedWordElement(CLUSTER_BASE + 11), DIRECT_1_TO_1),
            m(BydBatteryBoxCommercialC130.ChannelId.MIN_CELL_TEMPERATURE, new UnsignedWordElement(CLUSTER_BASE + 12), DIRECT_1_TO_1),
            m(BydBatteryBoxCommercialC130.ChannelId.MIN_TEMP_MODULE, new UnsignedWordElement(CLUSTER_BASE + 13), DIRECT_1_TO_1),
            m(BydBatteryBoxCommercialC130.ChannelId.MIN_TEMP_IN_MODULE, new UnsignedWordElement(CLUSTER_BASE + 14), DIRECT_1_TO_1),
            m(BydBatteryBoxCommercialC130.ChannelId.MAX_CELL_VOLTAGE, new UnsignedWordElement(CLUSTER_BASE + 15), DIRECT_1_TO_1),
            m(BydBatteryBoxCommercialC130.ChannelId.MAX_CELL_VOLT_MODULE, new UnsignedWordElement(CLUSTER_BASE + 16), DIRECT_1_TO_1),
            m(BydBatteryBoxCommercialC130.ChannelId.MAX_CELL_VOLT_CELL_NUM, new UnsignedWordElement(CLUSTER_BASE + 17), DIRECT_1_TO_1),
            m(BydBatteryBoxCommercialC130.ChannelId.MIN_CELL_VOLTAGE, new UnsignedWordElement(CLUSTER_BASE + 18), DIRECT_1_TO_1),
            m(BydBatteryBoxCommercialC130.ChannelId.MIN_CELL_VOLT_MODULE, new UnsignedWordElement(CLUSTER_BASE + 19), DIRECT_1_TO_1),
            m(BydBatteryBoxCommercialC130.ChannelId.MIN_CELL_VOLT_CELL_NUM, new UnsignedWordElement(CLUSTER_BASE + 20), DIRECT_1_TO_1),
            // Warning and Protection Events (bitfields assumed in registers 22-27)
            m(new UnsignedWordElement(CLUSTER_BASE + 22), v -> {
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.WARN_WHOLE_SET_OVERVOLTAGE, (v & 0x0001) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.WARN_WHOLE_SET_UNDERVOLTAGE, (v & 0x0002) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.WARN_CHARGING_OVERCURRENT, (v & 0x0004) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.WARN_DISCHARGING_OVERCURRENT, (v & 0x0008) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.WARN_CHARGING_HIGH_TEMP, (v & 0x0010) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.WARN_CHARGING_LOW_TEMP, (v & 0x0020) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.WARN_BMU_COMM_FAILURE, (v & 0x0040) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.WARN_TEMP_IMBALANCE, (v & 0x0080) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.WARN_CELL_VOLTAGE_IMBALANCE, (v & 0x0100) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.WARN_SOC_TOO_LOW, (v & 0x0200) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.WARN_LOW_INSULATION, (v & 0x0400) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.WARN_CELL_OVERVOLTAGE, (v & 0x0800) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.WARN_CELL_UNDERVOLTAGE, (v & 0x1000) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.WARN_DISCHARGE_HIGH_TEMP, (v & 0x2000) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.WARN_DISCHARGE_LOW_TEMP, (v & 0x4000) != 0);
            }),
            m(new UnsignedWordElement(CLUSTER_BASE + 23), v -> {
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.PRIMARY_WHOLE_SET_OVERVOLTAGE, (v & 0x0001) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.PRIMARY_WHOLE_SET_UNDERVOLTAGE, (v & 0x0002) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.PRIMARY_CHARGE_OVERCURRENT, (v & 0x0004) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.PRIMARY_DISCHARGE_OVERCURRENT, (v & 0x0008) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.PRIMARY_CHARGE_HIGH_TEMP, (v & 0x0010) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.PRIMARY_CHARGE_LOW_TEMP, (v & 0x0020) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.PRIMARY_BMU_COMM_FAILURE, (v & 0x0040) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.PRIMARY_TEMP_IMBALANCE, (v & 0x0080) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.PRIMARY_LOW_INSULATION, (v & 0x0100) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.PRIMARY_CELL_IMBALANCE, (v & 0x0200) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.PRIMARY_SOC_TOO_LOW, (v & 0x0400) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.PRIMARY_CELL_OVERVOLTAGE, (v & 0x0800) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.PRIMARY_CELL_UNDERVOLTAGE, (v & 0x1000) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.PRIMARY_DISCHARGE_HIGH_TEMP, (v & 0x2000) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.PRIMARY_DISCHARGE_LOW_TEMP, (v & 0x4000) != 0);
            }),
            // Secondary protections (assumed in CLUSTER_BASE + 24)
            m(new UnsignedWordElement(CLUSTER_BASE + 24), v -> {
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.SECONDARY_WHOLE_SET_OVERVOLTAGE, (v & 0x0001) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.SECONDARY_WHOLE_SET_UNDERVOLTAGE, (v & 0x0002) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.SECONDARY_CHARGE_OVERCURRENT, (v & 0x0004) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.SECONDARY_DISCHARGE_OVERCURRENT, (v & 0x0008) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.SECONDARY_CHARGE_HIGH_TEMP, (v & 0x0010) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.SECONDARY_CHARGE_LOW_TEMP, (v & 0x0020) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.SECONDARY_MODULE_FAULT, (v & 0x0040) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.SECONDARY_EXTERNAL_PROTECTION, (v & 0x0080) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.SECONDARY_TEMP_IMBALANCE, (v & 0x0100) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.SECONDARY_LOW_INSULATION, (v & 0x0200) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.SECONDARY_CELL_IMBALANCE, (v & 0x0400) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.SECONDARY_SYSTEM_TEMP_HIGH, (v & 0x0800) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.SECONDARY_CELL_OVERVOLTAGE, (v & 0x1000) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.SECONDARY_CELL_UNDERVOLTAGE, (v & 0x2000) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.SECONDARY_DISCHARGE_HIGH_TEMP, (v & 0x4000) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.SECONDARY_DISCHARGE_LOW_TEMP, (v & 0x8000) != 0);
            }),
            m(BydBatteryBoxCommercialC130.ChannelId.NEG_HALF_CLUSTER_CURRENT, new SignedWordElement(CLUSTER_BASE + 33), v -> toSigned16bit(v) * 100),
            m(BydBatteryBoxCommercialC130.ChannelId.CYCLE_COUNT, new UnsignedWordElement(CLUSTER_BASE + 34), DIRECT_1_TO_1),
            m(BydBatteryBoxCommercialC130.ChannelId.ACC_CHG_AH, new UnsignedWordElement(CLUSTER_BASE + 35), DIRECT_1_TO_1),
            m(BydBatteryBoxCommercialC130.ChannelId.ACC_DISCHG_AH, new UnsignedWordElement(CLUSTER_BASE + 36), DIRECT_1_TO_1),
            m(BydBatteryBoxCommercialC130.ChannelId.CURRENT_MAX_CHG_POWER, new UnsignedWordElement(CLUSTER_BASE + 37), v -> v * 100), // 0.1kW to W
            m(BydBatteryBoxCommercialC130.ChannelId.CURRENT_MAX_DISCHG_POWER, new UnsignedWordElement(CLUSTER_BASE + 38), v -> v * 100), // 0.1kW to W
            m(BydBatteryBoxCommercialC130.ChannelId.ACC_CHG_KWH, new UnsignedWordElement(CLUSTER_BASE + 39), DIRECT_1_TO_1),
            m(BydBatteryBoxCommercialC130.ChannelId.ACC_DISCHG_KWH, new UnsignedWordElement(CLUSTER_BASE + 40), DIRECT_1_TO_1),
            m(BydBatteryBoxCommercialC130.ChannelId.CURRENT_CHGABLE_KWH, new UnsignedWordElement(CLUSTER_BASE + 43), DIRECT_1_TO_1),
            m(BydBatteryBoxCommercialC130.ChannelId.CURRENT_DISCHGABLE_KWH, new UnsignedWordElement(CLUSTER_BASE + 44), DIRECT_1_TO_1)
        ));

        // BMU Status (bitfields assumed in CLUSTER_BASE + 3, 4)
        protocol.addTask(new FC3ReadRegistersTask(CLUSTER_BASE + 3, Priority.LOW,
            m(new UnsignedWordElement(CLUSTER_BASE + 3), v -> {
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.BMU1_WORK_STATUS,  (v & 0x0001) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.BMU2_WORK_STATUS,  (v & 0x0002) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.BMU3_WORK_STATUS,  (v & 0x0004) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.BMU4_WORK_STATUS,  (v & 0x0008) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.BMU5_WORK_STATUS,  (v & 0x0010) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.BMU6_WORK_STATUS,  (v & 0x0020) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.BMU7_WORK_STATUS,  (v & 0x0040) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.BMU8_WORK_STATUS,  (v & 0x0080) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.BMU9_WORK_STATUS,  (v & 0x0100) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.BMU10_WORK_STATUS, (v & 0x0200) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.BMU11_WORK_STATUS, (v & 0x0400) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.BMU12_WORK_STATUS, (v & 0x0800) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.BMU13_WORK_STATUS, (v & 0x1000) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.BMU14_WORK_STATUS, (v & 0x2000) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.BMU15_WORK_STATUS, (v & 0x4000) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.BMU16_WORK_STATUS, (v & 0x8000) != 0);
            }),
            m(new UnsignedWordElement(CLUSTER_BASE + 4), v -> {
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.BMU17_WORK_STATUS, (v & 0x0001) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.BMU18_WORK_STATUS, (v & 0x0002) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.BMU19_WORK_STATUS, (v & 0x0004) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.BMU20_WORK_STATUS, (v & 0x0008) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.BMU21_WORK_STATUS, (v & 0x0010) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.BMU22_WORK_STATUS, (v & 0x0020) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.BMU23_WORK_STATUS, (v & 0x0040) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.BMU24_WORK_STATUS, (v & 0x0080) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.BMU25_WORK_STATUS, (v & 0x0100) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.BMU26_WORK_STATUS, (v & 0x0200) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.BMU27_WORK_STATUS, (v & 0x0400) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.BMU28_WORK_STATUS, (v & 0x0800) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.BMU29_WORK_STATUS, (v & 0x1000) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.BMU30_WORK_STATUS, (v & 0x2000) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.BMU31_WORK_STATUS, (v & 0x4000) != 0);
                this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.BMU32_WORK_STATUS, (v & 0x8000) != 0);
            })
        ));

        // Per-Module Cell Data
        for (int i = 0; i < this.config.numberOfModules(); i++) {
            int moduleBase = CELL_BASE + (i * CELL_OFFSET);
            String prefix = "MODULE_" + (i + 1) + "_";
            protocol.addTask(new FC3ReadRegistersTask(moduleBase, Priority.LOW,
                m(channelId(prefix + "CELL_VOLTAGE_1"), new UnsignedWordElement(moduleBase), DIRECT_1_TO_1),
                m(channelId(prefix + "CELL_VOLTAGE_2"), new UnsignedWordElement(moduleBase + 1), DIRECT_1_TO_1),
                m(channelId(prefix + "CELL_VOLTAGE_3"), new UnsignedWordElement(moduleBase + 2), DIRECT_1_TO_1),
                m(channelId(prefix + "CELL_VOLTAGE_4"), new UnsignedWordElement(moduleBase + 3), DIRECT_1_TO_1),
                m(channelId(prefix + "CELL_VOLTAGE_5"), new UnsignedWordElement(moduleBase + 4), DIRECT_1_TO_1),
                m(channelId(prefix + "CELL_VOLTAGE_6"), new UnsignedWordElement(moduleBase + 5), DIRECT_1_TO_1),
                m(channelId(prefix + "CELL_VOLTAGE_7"), new UnsignedWordElement(moduleBase + 6), DIRECT_1_TO_1),
                m(channelId(prefix + "CELL_VOLTAGE_8"), new UnsignedWordElement(moduleBase + 7), DIRECT_1_TO_1),
                m(channelId(prefix + "CELL_VOLTAGE_9"), new UnsignedWordElement(moduleBase + 8), DIRECT_1_TO_1),
                m(channelId(prefix + "CELL_VOLTAGE_10"), new UnsignedWordElement(moduleBase + 9), DIRECT_1_TO_1),
                m(channelId(prefix + "CELL_VOLTAGE_11"), new UnsignedWordElement(moduleBase + 10), DIRECT_1_TO_1),
                m(channelId(prefix + "CELL_VOLTAGE_12"), new UnsignedWordElement(moduleBase + 11), DIRECT_1_TO_1),
                m(channelId(prefix + "CELL_VOLTAGE_13"), new UnsignedWordElement(moduleBase + 12), DIRECT_1_TO_1),
                m(channelId(prefix + "CELL_VOLTAGE_14"), new UnsignedWordElement(moduleBase + 13), DIRECT_1_TO_1),
                m(channelId(prefix + "CELL_VOLTAGE_15"), new UnsignedWordElement(moduleBase + 14), DIRECT_1_TO_1),
                m(channelId(prefix + "CELL_VOLTAGE_16"), new UnsignedWordElement(moduleBase + 15), DIRECT_1_TO_1),
                m(channelId(prefix + "CELL_VOLTAGE_17"), new UnsignedWordElement(moduleBase + 16), DIRECT_1_TO_1),
                m(channelId(prefix + "CELL_VOLTAGE_18"), new UnsignedWordElement(moduleBase + 17), DIRECT_1_TO_1),
                m(channelId(prefix + "CELL_VOLTAGE_19"), new UnsignedWordElement(moduleBase + 18), DIRECT_1_TO_1),
                m(channelId(prefix + "CELL_VOLTAGE_20"), new UnsignedWordElement(moduleBase + 19), DIRECT_1_TO_1),
                m(channelId(prefix + "CELL_VOLTAGE_21"), new UnsignedWordElement(moduleBase + 20), DIRECT_1_TO_1),
                m(channelId(prefix + "CELL_VOLTAGE_22"), new UnsignedWordElement(moduleBase + 21), DIRECT_1_TO_1),
                m(channelId(prefix + "CELL_VOLTAGE_23"), new UnsignedWordElement(moduleBase + 22), DIRECT_1_TO_1),
                m(channelId(prefix + "CELL_VOLTAGE_24"), new UnsignedWordElement(moduleBase + 23), DIRECT_1_TO_1),
                m(channelId(prefix + "TEMPERATURE_1"), new UnsignedWordElement(moduleBase + 24), DIRECT_1_TO_1),
                m(channelId(prefix + "TEMPERATURE_2"), new UnsignedWordElement(moduleBase + 25), DIRECT_1_TO_1),
                m(channelId(prefix + "TEMPERATURE_3"), new UnsignedWordElement(moduleBase + 26), DIRECT_1_TO_1),
                m(channelId(prefix + "TEMPERATURE_4"), new UnsignedWordElement(moduleBase + 27), DIRECT_1_TO_1),
                m(channelId(prefix + "TEMPERATURE_5"), new UnsignedWordElement(moduleBase + 28), DIRECT_1_TO_1),
                m(channelId(prefix + "TEMPERATURE_6"), new UnsignedWordElement(moduleBase + 29), DIRECT_1_TO_1),
                m(channelId(prefix + "TEMPERATURE_7"), new UnsignedWordElement(moduleBase + 30), DIRECT_1_TO_1),
                m(channelId(prefix + "TEMPERATURE_8"), new UnsignedWordElement(moduleBase + 31), DIRECT_1_TO_1)
            ));
        }

        // Product Info (0x7050, 36 registers)
        protocol.addTask(new FC3ReadRegistersTask(PRODUCT_BASE, Priority.LOW,
            m(BydBatteryBoxCommercialC130.ChannelId.MODEL, new StringWordElement(PRODUCT_BASE, 9)),
            m(BydBatteryBoxCommercialC130.ChannelId.SERIAL_NUMBER, new StringWordElement(PRODUCT_BASE + 12, 8)),
            m(BydBatteryBoxCommercialC130.ChannelId.FIRMWARE_VERSION, new StringWordElement(PRODUCT_BASE + 20, 2)),
            m(BydBatteryBoxCommercialC130.ChannelId.HARDWARE_VERSION, new StringWordElement(PRODUCT_BASE + 22, 2)),
            m(BydBatteryBoxCommercialC130.ChannelId.FIRMWARE_COMPILE_TIME, new StringWordElement(PRODUCT_BASE + 24, 12))
        ));

        return protocol;
    }

    private ChannelId channelId(String id) {
        return BydBatteryBoxCommercialC130.ChannelId.valueOf(id);
    }

    private int toSigned16bit(int value) {
        if (value > 32767) {
            value -= 65536;
        }
        return value;
    }

    @Override
    public void handleEvent(Event event) {
        if (!this.isEnabled()) {
            return;
        }
        if (EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE.equals(event.getTopic())) {
            this.updateChannels();
        }
    }

    private void updateChannels() {
        this._setChannelValue(BydBatteryBoxCommercialC130.ChannelId.MODULE_QTY, this.config.numberOfModules());
        this._setChannelValue(Battery.ChannelId.VOLTAGE, this.channel(BydBatteryBoxCommercialC130.ChannelId.TOTAL_VOLTAGE).value().orElse(0));
        this._setChannelValue(Battery.ChannelId.CURRENT, this.channel(BydBatteryBoxCommercialC130.ChannelId.TOTAL_CURRENT).value().orElse(0));
        this._setChannelValue(Battery.ChannelId.SOC, this.channel(BydBatteryBoxCommercialC130.ChannelId.TOTAL_SOC).value().orElse(0));
        this._setChannelValue(Battery.ChannelId.SOH, this.channel(BydBatteryBoxCommercialC130.ChannelId.TOTAL_SOH).value().orElse(0));
        this._setChannelValue(Battery.ChannelId.MAX_CELL_VOLTAGE, this.channel(BydBatteryBoxCommercialC130.ChannelId.MAX_CELL_VOLTAGE).value().orElse(0));
        this._setChannelValue(Battery.ChannelId.MIN_CELL_VOLTAGE, this.channel(BydBatteryBoxCommercialC130.ChannelId.MIN_CELL_VOLTAGE).value().orElse(0));
        this._setChannelValue(Battery.ChannelId.MAX_CELL_TEMPERATURE, this.channel(BydBatteryBoxCommercialC130.ChannelId.MAX_CELL_TEMPERATURE).value().orElse(0));
        this._setChannelValue(Battery.ChannelId.MIN_CELL_TEMPERATURE, this.channel(BydBatteryBoxCommercialC130.ChannelId.MIN_CELL_TEMPERATURE).value().orElse(0));
    }

    @Override
    public void setStartStop(StartStop value) {
        // This battery system doesn't support start/stop control
        // It's always running when enabled
    }

    @Override
    public String debugLog() {
        return String.format("SOC: %d%% | Voltage: %d mV | Current: %d mA",
            this.getSocChannel().value().orElse(0),
            this.getVoltageChannel().value().orElse(0),
            this.getCurrentChannel().value().orElse(0));
    }
}