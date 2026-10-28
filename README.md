# SwiftTransit — Next-Gen Bus Reservation & Fleet Management System

[![Java](https://img.shields.io/badge/Java-17%2B-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)](https://www.oracle.com/java/)
[![Database](https://img.shields.io/badge/Database-SQLite%203-003B57?style=for-the-badge&logo=sqlite&logoColor=white)](https://www.sqlite.org/)
[![GUI](https://img.shields.io/badge/GUI-Java%20Swing%20%2F%20AWT-blue?style=for-the-badge)](https://docs.oracle.com/javase/tutorial/uiswing/)
[![License](https://img.shields.io/badge/License-MIT-green.svg?style=for-the-badge)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Windows%20%7C%20Linux%20%7C%20macOS-lightgrey?style=for-the-badge)](https://github.com)

**SwiftTransit** is a modern, enterprise-grade Java desktop application designed for high-efficiency passenger bus ticketing, interactive seat allocation, and relational fleet data management. Powered by an embedded **SQLite 3** relational database (`bus_reservation.db`) using standard JDBC, it guarantees ACID transaction compliance, strict duplicate-booking prevention, and sub-millisecond query performance.

---

## 📸 Key Application Modules

```
+---------------------------------------------------------------------------------------------------+
|                                 SwiftTransit Main Interface                                       |
+------------------------------------+----------------------------------+---------------------------+
|      [1] Search & Book Tickets     |    [2] My Confirmed Bookings     | [3] Database & Admin Hub  |
+------------------------------------+----------------------------------+---------------------------+
| * Route Origin & Destination Query | * Real-time Booked Tickets View  | * Live KPI Metric Cards   |
| * Dynamic Available Buses Grid     | * Printable Official Receipt     | * Live `buses` Table View |
| * Realistic 2x2 Coach Seat Map     | * One-Click Seat Cancellation    | * Live `tickets` Table    |
| * Passenger Form & Validation      | * Ticket Lookup by ID Search     | * Interactive SQL Console |
+------------------------------------+----------------------------------+---------------------------+
```

---

## ✨ Features & Capabilities

### 1. 💺 Realistic Coach Seat Map
* **Aisle-Divided 2x2 Geometry:** Faithfully mimics realistic passenger buses with Left Window/Aisle, Center Walkway, and Right Aisle/Window seats.
* **Driver Cabin Orientation:** Features a distinct `<< FRONT / DRIVER` visual indicator.
* **Live Status Visualizer:** 
  - 🟢 **Emerald Green:** Available for instant booking.
  - 🔴 **Crimson Red:** Locked & confirmed in the SQLite database.
  - 🟠 **Amber Orange:** Currently selected seat.

### 2. 🛡️ Database-Enforced Data Integrity
* **Strict Double-Booking Prevention:** A database-level `UNIQUE(bus_id, seat_no)` constraint guarantees that two passengers can never book the exact same seat on the same bus, even under rapid concurrent attempts.
* **ACID Transactions:** Seat reservation and ticket record creation execute in an atomic transaction (`conn.commit()` / `conn.rollback()`).

### 3. 🧾 Official Ticket Receipt Generator
* Generates a formal, printable ticket receipt with ticket ID, passenger details, route, departure timestamp, allocated seat number, and total fare.

### 4. 📊 Live Fleet & Revenue Dashboard
* Real-time metrics tracking:
  - **Database Status:** Engine connectivity indicator (`● SQLite 3 Connected`).
  - **Total Routes:** Count of active registered buses.
  - **Active Bookings:** Real-time count of confirmed tickets.
  - **Total Revenue:** Automated live gross revenue summation in Rupees (`Rs.`).

### 5. 💻 Interactive SQL Query Console
* Run live SQL queries directly on `bus_reservation.db` from within the application GUI.
* Built-in one-click query presets:
  - `SELECT * FROM buses;`
  - `SELECT * FROM tickets;`
  - `SELECT bus_id, COUNT(*) AS total_booked, SUM(fare) AS total_revenue FROM tickets GROUP BY bus_id;`

### 6. 🔌 Resilient Driver Architecture
* Uses the **DriverShim** pattern to seamlessly load SQLite JDBC drivers dynamically without class loader conflicts.

---

## 🗄️ Relational Database Schema (`bus_reservation.db`)

### Entity-Relationship Diagram

```
+------------------------------------+              +------------------------------------+
|               buses                |              |              tickets               |
+------------------------------------+              +------------------------------------+
| PK  bus_id        TEXT             |<-------+     | PK  ticket_id      TEXT            |
|     name          TEXT             |        |     |     passenger_name TEXT            |
|     source        TEXT             |        |     |     age            INTEGER         |
|     destination   TEXT             |        +-----| FK  bus_id         TEXT            |
|     departure     TEXT             |              |     seat_no        INTEGER         |
|     total_seats   INTEGER          |              |     fare           REAL            |
|     fare          REAL             |              |     booked_at      TEXT            |
+------------------------------------+              +------------------------------------+
                                                    * CONSTRAINT: UNIQUE(bus_id, seat_no)
```

### Table Definitions

#### 1. `buses` Table
| Column Name | Data Type | Constraints | Description |
|---|---|---|---|
| `bus_id` | `TEXT` | `PRIMARY KEY` | Unique route/bus identifier (e.g., `B101`) |
| `name` | `TEXT` | `NOT NULL` | Fleet service name (e.g., *Express Deluxe*) |
| `source` | `TEXT` | `NOT NULL` | Origin departure city |
| `destination` | `TEXT` | `NOT NULL` | Destination arrival city |
| `departure` | `TEXT` | `NOT NULL` | Scheduled departure time (24h format) |
| `total_seats` | `INTEGER` | `NOT NULL, CHECK(> 0)` | Total coach capacity (e.g., `20`) |
| `fare` | `REAL` | `NOT NULL, CHECK(>= 0)` | Ticket fare in INR (Rs.) |

#### 2. `tickets` Table
| Column Name | Data Type | Constraints | Description |
|---|---|---|---|
| `ticket_id` | `TEXT` | `PRIMARY KEY` | Unique booking reference (e.g., `T1001`) |
| `passenger_name` | `TEXT` | `NOT NULL` | Passenger full legal name |
| `age` | `INTEGER` | `NOT NULL, CHECK(1-120)` | Passenger age in years |
| `bus_id` | `TEXT` | `FOREIGN KEY REFERENCES buses(bus_id)` | Associated bus route |
| `seat_no` | `INTEGER` | `NOT NULL, CHECK(> 0)` | Allocated seat number |
| `fare` | `REAL` | `NOT NULL, CHECK(>= 0)` | Amount collected |
| `booked_at` | `TEXT` | `NOT NULL` | Booking timestamp (`dd-MM-yyyy HH:mm`) |

---

## 🚀 Quick Start Guide

### Prerequisites
- **Java Development Kit (JDK 8 or higher)**: JDK 11, 17, 21, or 25 recommended.
- Ensure `java` and `javac` are available in your system `PATH`.

---

### Method 1: One-Click Runner (Windows)
Double-click **`run.bat`** in the project folder. The script will automatically:
1. Verify the SQLite JDBC driver (auto-downloading `sqlite-jdbc` if missing).
2. Compile `BusReservationGUI.java`.
3. Launch the **SwiftTransit** application.

---

### Method 2: Command Line (PowerShell / Command Prompt)

1. Open terminal in the project directory:
   ```cmd
   cd c:\Users\DHARANIDHARAN\OneDrive\Documents\java\micro
   ```

2. Compile the application with wildcard classpath:
   ```cmd
   javac -cp ".;*" BusReservationGUI.java
   ```

3. Launch the GUI:
   ```cmd
   java -cp ".;*" BusReservationGUI
   ```

---

### Method 3: Running inside VS Code
1. Open the project folder in VS Code.
2. Open `BusReservationGUI.java`.
3. Click the **▶ Run Java** button in the top-right corner.

---

## 📁 Project Directory Structure

```
c:\Users\DHARANIDHARAN\OneDrive\Documents\java\micro\
├── BusReservationGUI.java        # Main Application Source (GUI, Logic, DriverShim, DB Manager)
├── schema.sql                    # Standalone SQLite DDL Table Schemas & Initial Seed Data
├── run.bat                       # One-Click Automated Build & Execution Batch Script
├── README.md                     # Comprehensive Project Documentation
├── LICENSE                       # Open-Source MIT License File
├── .gitignore                    # Git Ignore Configuration
├── bus_reservation.db            # Embedded SQLite Database (created on first run)
├── sqlite-jdbc-3.36.0.3.jar      # Self-Contained SQLite JDBC Driver
├── slf4j-api.jar                 # SLF4J Core API Library
└── slf4j-nop.jar                 # SLF4J NOP Provider Library
```

---

## 🛠️ Technology Stack

| Layer | Component | Description |
|---|---|---|
| **Language** | Java SE (JDK 17+) | Modern object-oriented implementation |
| **User Interface** | Java Swing & AWT | Native Windows Look-and-Feel (`Segoe UI`) |
| **Persistence** | SQLite 3 (`bus_reservation.db`) | Zero-configuration serverless embedded relational database |
| **Driver Connection** | JDBC + DriverShim Pattern | Dynamic classloader bridging without security policy conflicts |
| **Build / Execution** | Batch Script (`run.bat`) | Automated dependency download, compilation, and boot |

---

## 📄 License

This project is licensed under the **MIT License** — see the [LICENSE](LICENSE) file for details.

```
Copyright (c) 2026 DHARANIDHARAN
```