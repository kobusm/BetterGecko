import Foundation

struct PerformanceHistoryResponse: Decodable {
    let timestamps: [String]?
    let temperatures: [Double]?
    let pvVoltage: [Int]?
    let mpptVoltage: [Int]?
    let pvInPower: [Int]?
    let pvOutPower: [Int]?
    let acOutPower: [Int]?
    let state: [String]?
    let pvUsageWeek: Int?    // minutes solar was active this week
    let acUsageWeek: Int?    // minutes element was active this week
    let savingsWeek: Double? // estimated savings in ZAR this week

}

struct UVIndexResponse: Decodable {
    let uvIndex: Double?
    let value: Double?

    var index: Double? { uvIndex ?? value }
}

struct HasControlResponse: Decodable {
    let hasControl: Bool?
    let control: Bool?

    var controlled: Bool { hasControl ?? control ?? false }
}


struct DeviceAPI {
    static let shared = DeviceAPI()
    private let client = APIClient.shared

    func getPerformanceHistory(gsn: String) async throws -> PerformanceHistoryResponse {
        try await client.get("/app/getPerformanceHistory", params: ["geckoSerialNumber": gsn], as: PerformanceHistoryResponse.self)
    }

    func getUVIndex(gsn: String) async throws -> UVIndexResponse {
        try await client.get("/uv/uvindex", params: ["geckoSerialNumber": gsn], as: UVIndexResponse.self)
    }

    func hasControl(gsn: String) async throws -> HasControlResponse {
        try await client.get("/app/hasControl", params: ["geckoSerialNumber": gsn], as: HasControlResponse.self)
    }

    /// Sets both AC and PV temperature setpoints in a single API call.
    /// Body: { "geckoSerialNumber": "...", "acMax": 50, "pvMax": 60 }
    func setTemperature(gsn: String, acMax: Double, pvMax: Double) async throws {
        let _: EmptyResponse = try await client.post("/app/setTemperature", body: ["geckoSerialNumber": gsn, "acMax": acMax, "pvMax": pvMax])
    }

    /// Sets operating mode using string values: "ALL_OFF", "PV_ONLY", "AC_AND_PV", "AC_ONLY"
    func setMode(gsn: String, geckoMode: String) async throws {
        let _: EmptyResponse = try await client.post("/app/setMode", body: ["geckoSerialNumber": gsn, "geckoMode": geckoMode])
    }

}
