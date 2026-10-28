-- ==============================================================
-- Bus Ticket Reservation System - SQLite Database Schema
-- File: bus_reservation.db
-- ==============================================================

PRAGMA foreign_keys = ON;

-- --------------------------------------------------------------
-- 1. Table: buses
-- Stores bus routes, timings, fare, and seat capacity.
-- --------------------------------------------------------------
CREATE TABLE IF NOT EXISTS buses (
    bus_id       TEXT PRIMARY KEY,
    name         TEXT NOT NULL,
    source       TEXT NOT NULL,
    destination  TEXT NOT NULL,
    departure    TEXT NOT NULL,
    total_seats  INTEGER NOT NULL CHECK(total_seats > 0),
    fare         REAL NOT NULL CHECK(fare >= 0)
);

-- --------------------------------------------------------------
-- 2. Table: tickets
-- Stores passenger bookings linked to a specific bus and seat.
-- The UNIQUE(bus_id, seat_no) constraint guarantees no double booking!
-- --------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tickets (
    ticket_id      TEXT PRIMARY KEY,
    passenger_name TEXT NOT NULL,
    age            INTEGER NOT NULL CHECK(age BETWEEN 1 AND 120),
    bus_id         TEXT NOT NULL,
    seat_no        INTEGER NOT NULL CHECK(seat_no > 0),
    fare           REAL NOT NULL CHECK(fare >= 0),
    booked_at      TEXT NOT NULL,
    FOREIGN KEY (bus_id) REFERENCES buses(bus_id) ON DELETE CASCADE,
    UNIQUE (bus_id, seat_no)
);

-- --------------------------------------------------------------
-- Initial Sample Seed Data for Buses
-- --------------------------------------------------------------
INSERT OR IGNORE INTO buses (bus_id, name, source, destination, departure, total_seats, fare) VALUES
('B101', 'Express Deluxe', 'Coimbatore', 'Chennai',   '21:30', 20, 650.00),
('B102', 'Night Rider',    'Coimbatore', 'Bangalore', '22:00', 16, 550.00),
('B103', 'City Link',      'Chennai',    'Madurai',   '20:15', 24, 480.00),
('B104', 'Sleeper Plus',   'Bangalore',  'Hyderabad', '19:45', 12, 900.00);
