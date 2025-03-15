package io.openems.edge.battery.bydcommercial;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(
    name = "Battery BYD Battery-Box Commercial C130 XXX",
    description = "Implements a generic BMS with Modbus communication. XXX"
)
public @interface Config {

    @AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
    String id() default "battery0";

    @AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID")
    String alias() default "";

    @AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
    boolean enabled() default true;

    @AttributeDefinition(name = "Modbus-ID", description = "ID of Modbus bridge.")
    String modbus_id() default "modbus0";

    @AttributeDefinition(name = "Modbus Unit-ID", description = "The Unit-ID of the Modbus device.")
    int modbusUnitId() default 1;

    @AttributeDefinition(name = "Host", description = "IP address or hostname of the Modbus device.")
    String host() default "192.168.0.20";

    @AttributeDefinition(name = "Port", description = "Port of the Modbus device.")
    int port() default 502;

    @AttributeDefinition(name = "Is SBMS?", description = "True if using SBMS, false for RBMS.")
    boolean isSbms() default false;

    @AttributeDefinition(name = "Number of Modules", description = "Number of battery modules (1-32)", min = "1", max = "32")
    int numberOfModules() default 1;

    String Modbus_target() default "";

    String webconsole_configurationFactory_nameHint() default "Battery BYD Battery-Box Commercial C130 [{id}]";
}