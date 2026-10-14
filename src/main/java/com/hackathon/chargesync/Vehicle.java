package com.hackathon.chargesync;

public class Vehicle {
    public String id;
    public String destination = "City center";
    public String connector = "CCS2";
    public String chargeMode = "NORMAL";

    public double batteryCapacityKwh;
    public double soc;
    public double targetSoc;
    public double maxKw;
    public int departureMinute;
    public double allocatedKw;
    public double energyDeliveredKwh;
    public double costInr;
    public String status;

    public Vehicle(String id, double capacity, double soc, double target, double maxKw, int departure) {
        this.id=id; this.batteryCapacityKwh=capacity; this.soc=soc; this.targetSoc=target;
        this.maxKw=maxKw; this.departureMinute=departure; this.status="WAITING";
        this.destination = switch (id) {
            case "EV-01" -> "City center";
            case "EV-02" -> "University";
            case "EV-03" -> "Airport";
            case "EV-04" -> "West market";
            case "EV-05" -> "Hospital";
            default -> "North district";
        };
    }

    public double energyNeededKwh() {
        return Math.max(0, (targetSoc - soc) * batteryCapacityKwh / 100.0);
    }
}
