package com.hackathon.chargesync;

public class VehicleRequest {
    public String id;
    public String destination;
    public String connector;
    public String chargeMode;

    public Double batteryCapacityKwh;
    public Double soc;
    public Double targetSoc;
    public Double maxKw;
    public Integer departureMinute;
}
