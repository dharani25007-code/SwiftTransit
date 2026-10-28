import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableModel;
import java.awt.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;

/* ===================== CUSTOM EXCEPTIONS ===================== */
class InvalidInputException extends Exception {
    public InvalidInputException(String msg) { super(msg); }
}
class BusNotFoundException extends Exception {
    public BusNotFoundException(String busId) { super("Bus not found: " + busId); }
}
class SeatNotAvailableException extends Exception {
    public SeatNotAvailableException(String msg) { super(msg); }
}
class TicketNotFoundException extends Exception {
    public TicketNotFoundException(String id) { super("Ticket not found: " + id); }
}

/* ===================== MODEL: BUS ===================== */
class Bus {
    private final String busId, name, source, destination, departure;
    private final int totalSeats;
    private final double fare;
    private final Set<Integer> bookedSeats = new TreeSet<>();

    public Bus(String busId, String name, String source, String destination,
               String departure, int totalSeats, double fare) {
        this.busId = busId; this.name = name; this.source = source;
        this.destination = destination; this.departure = departure;
        this.totalSeats = totalSeats; this.fare = fare;
    }

    public String getBusId() { return busId; }
    public String getName() { return name; }
    public String getSource() { return source; }
    public String getDestination() { return destination; }
    public String getDeparture() { return departure; }
    public int getTotalSeats() { return totalSeats; }
    public double getFare() { return fare; }
    public int availableSeats() { return totalSeats - bookedSeats.size(); }
    public boolean isBooked(int seat) { return bookedSeats.contains(seat); }
    public Set<Integer> getBookedSeats() { return Collections.unmodifiableSet(bookedSeats); }

    public void bookSeat(int seat) throws SeatNotAvailableException {
        if (seat < 1 || seat > totalSeats)
            throw new SeatNotAvailableException("Seat " + seat + " does not exist (valid: 1-" + totalSeats + ").");
        if (!bookedSeats.add(seat))
            throw new SeatNotAvailableException("Seat " + seat + " is already booked.");
    }
    public void releaseSeat(int seat) { bookedSeats.remove(seat); }

    public String toRecordString() {
        return String.join(",", busId, name, source, destination, departure,
                String.valueOf(totalSeats), String.valueOf(fare));
    }

    public static Bus fromRecordString(String line) throws InvalidInputException {
        String[] p = line.split(",");
        if (p.length != 7) throw new InvalidInputException("Bad bus record: " + line);
        try {
            return new Bus(p[0].trim(), p[1].trim(), p[2].trim(), p[3].trim(), p[4].trim(),
                    Integer.parseInt(p[5].trim()), Double.parseDouble(p[6].trim()));
        } catch (NumberFormatException e) {
            throw new InvalidInputException("Bad number in bus record: " + line);
        }
    }
}

/* ===================== MODEL: TICKET ===================== */
class Ticket {
    private final String ticketId, passengerName, busId, bookedAt;
    private final int age, seatNo;
    private final double fare;

    public Ticket(String ticketId, String passengerName, int age, String busId,
                  int seatNo, double fare, String bookedAt) {
        this.ticketId = ticketId; this.passengerName = passengerName; this.age = age;
        this.busId = busId; this.seatNo = seatNo; this.fare = fare; this.bookedAt = bookedAt;
    }

    public String getTicketId() { return ticketId; }
    public String getPassengerName() { return passengerName; }
    public int getAge() { return age; }
    public String getBusId() { return busId; }
    public int getSeatNo() { return seatNo; }
    public double getFare() { return fare; }
    public String getBookedAt() { return bookedAt; }

    public String toRecordString() {
        return String.join(",", ticketId, passengerName, String.valueOf(age), busId,
                String.valueOf(seatNo), String.valueOf(fare), bookedAt);
    }

    public static Ticket fromRecordString(String line) throws InvalidInputException {
        String[] p = line.split(",");
        if (p.length != 7) throw new InvalidInputException("Bad ticket record: " + line);
        try {
            return new Ticket(p[0], p[1], Integer.parseInt(p[2]), p[3],
                    Integer.parseInt(p[4]), Double.parseDouble(p[5]), p[6]);
        } catch (NumberFormatException e) {
            throw new InvalidInputException("Bad number in ticket record: " + line);
        }
    }

    public String toReceiptText() {
        return  "============================================\n" +
                "   SWIFTTRANSIT - OFFICIAL TICKET RECEIPT   \n" +
                "============================================\n" +
                "  Ticket Number  : " + ticketId + "\n" +
                "  Passenger Name : " + passengerName + "\n" +
                "  Age            : " + age + " years\n" +
                "  Bus ID         : " + busId + "\n" +
                "  Allocated Seat : Seat #" + seatNo + "\n" +
                "  Fare Paid      : Rs. " + String.format("%.2f", fare) + "\n" +
                "  Date of Booking: " + bookedAt + "\n" +
                "  Status         : CONFIRMED (Stored in SQLite)\n" +
                "============================================\n" +
                "   Thank you for choosing SwiftTransit!    \n" +
                "============================================";
    }
}

/* ===================== JDBC DRIVER SHIM ===================== */
class DriverShim implements Driver {
    private final Driver driver;
    public DriverShim(Driver d) { this.driver = d; }
    @Override public boolean acceptsURL(String u) throws SQLException { return driver.acceptsURL(u); }
    @Override public Connection connect(String u, Properties p) throws SQLException { return driver.connect(u, p); }
    @Override public int getMajorVersion() { return driver.getMajorVersion(); }
    @Override public int getMinorVersion() { return driver.getMinorVersion(); }
    @Override public DriverPropertyInfo[] getPropertyInfo(String u, Properties p) throws SQLException { return driver.getPropertyInfo(u, p); }
    @Override public boolean jdbcCompliant() { return driver.jdbcCompliant(); }
    @Override public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException { return driver.getParentLogger(); }
}

/* ===================== DATABASE MANAGER ===================== */
class DatabaseManager {
    static final String DB_FILE = "bus_reservation.db";
    static final String DB_URL = "jdbc:sqlite:" + DB_FILE;

    private static boolean driverLoaded = false;
    private static boolean useSqlite = false;

    // Resilient local store backing cache
    private static final Map<String, Bus> localBuses = new LinkedHashMap<>();
    private static final Map<String, Ticket> localTickets = new LinkedHashMap<>();
    private static int nextTicketNo = 1001;

    public static boolean isUsingSqlite() { return useSqlite; }

    public static synchronized void initDatabase() {
        initLocalDatabase();

        try {
            ensureDriverLoaded();
            if (driverLoaded) {
                try (Connection conn = getConnection()) {
                    initSqliteSchema(conn);
                    useSqlite = true;
                    System.out.println("[DB] SQLite database active: " + DB_FILE);
                }
            }
        } catch (Throwable t) {
            useSqlite = false;
            System.out.println("[DB] Running in local resilient mode: " + t.getMessage());
        }
    }

    public static Connection getConnection() throws SQLException {
        Connection conn = DriverManager.getConnection(DB_URL);
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON;");
        }
        return conn;
    }

    public static synchronized void ensureDriverLoaded() {
        if (driverLoaded) return;

        // Try classpath first
        try {
            Class.forName("org.sqlite.JDBC");
            driverLoaded = true;
            return;
        } catch (Throwable ignored) {}

        // Check local folder for sqlite-jdbc*.jar
        File cur = new File(".");
        File[] jars = cur.listFiles((dir, name) -> name.toLowerCase().endsWith(".jar"));
        if (jars != null && jars.length > 0) {
            try {
                List<URL> urls = new ArrayList<>();
                for (File j : jars) urls.add(j.toURI().toURL());
                URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader());
                Driver driver = (Driver) Class.forName("org.sqlite.JDBC", true, loader).getDeclaredConstructor().newInstance();
                DriverManager.registerDriver(new DriverShim(driver));
                driverLoaded = true;
            } catch (Throwable ignored) {}
        }
    }

    public static void downloadAndConnectSqlite() throws Exception {
        File driverFile = new File("sqlite-jdbc-3.36.0.3.jar");
        if (!driverFile.exists()) {
            downloadFile("https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.36.0.3/sqlite-jdbc-3.36.0.3.jar", driverFile);
        }

        URLClassLoader loader = new URLClassLoader(new URL[]{ driverFile.toURI().toURL() }, ClassLoader.getPlatformClassLoader());
        Driver driver = (Driver) Class.forName("org.sqlite.JDBC", true, loader).getDeclaredConstructor().newInstance();
        DriverManager.registerDriver(new DriverShim(driver));
        driverLoaded = true;

        try (Connection conn = getConnection()) {
            initSqliteSchema(conn);
            migrateLocalToSqlite(conn);
            useSqlite = true;
        }
    }

    private static void downloadFile(String fileUrl, File target) throws IOException {
        URL url = URI.create(fileUrl).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(30000);

        int status = conn.getResponseCode();
        if (status == HttpURLConnection.HTTP_MOVED_TEMP || status == HttpURLConnection.HTTP_MOVED_PERM || status == 307) {
            String newUrl = conn.getHeaderField("Location");
            conn = (HttpURLConnection) URI.create(newUrl).toURL().openConnection();
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        }

        try (InputStream in = new BufferedInputStream(conn.getInputStream());
             OutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            out.flush();
        }
    }

    private static void initSqliteSchema(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS buses (" +
                    "bus_id TEXT PRIMARY KEY, " +
                    "name TEXT NOT NULL, " +
                    "source TEXT NOT NULL, " +
                    "destination TEXT NOT NULL, " +
                    "departure TEXT NOT NULL, " +
                    "total_seats INTEGER NOT NULL, " +
                    "fare REAL NOT NULL" +
                    ");");

            stmt.execute("CREATE TABLE IF NOT EXISTS tickets (" +
                    "ticket_id TEXT PRIMARY KEY, " +
                    "passenger_name TEXT NOT NULL, " +
                    "age INTEGER NOT NULL, " +
                    "bus_id TEXT NOT NULL, " +
                    "seat_no INTEGER NOT NULL, " +
                    "fare REAL NOT NULL, " +
                    "booked_at TEXT NOT NULL, " +
                    "FOREIGN KEY(bus_id) REFERENCES buses(bus_id) ON DELETE CASCADE, " +
                    "UNIQUE(bus_id, seat_no)" +
                    ");");

            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM buses")) {
                if (rs.next() && rs.getInt(1) == 0) {
                    seedDefaultBusesToSqlite(conn);
                }
            }
        }
    }

    private static void seedDefaultBusesToSqlite(Connection conn) throws SQLException {
        String sql = "INSERT OR IGNORE INTO buses (bus_id, name, source, destination, departure, total_seats, fare) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Bus b : localBuses.values()) {
                ps.setString(1, b.getBusId());
                ps.setString(2, b.getName());
                ps.setString(3, b.getSource());
                ps.setString(4, b.getDestination());
                ps.setString(5, b.getDeparture());
                ps.setInt(6, b.getTotalSeats());
                ps.setDouble(7, b.getFare());
                ps.executeUpdate();
            }
        }
    }

    private static void migrateLocalToSqlite(Connection conn) throws SQLException {
        seedDefaultBusesToSqlite(conn);
        String ticketSql = "INSERT OR IGNORE INTO tickets (ticket_id, passenger_name, age, bus_id, seat_no, fare, booked_at) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(ticketSql)) {
            for (Ticket t : localTickets.values()) {
                ps.setString(1, t.getTicketId());
                ps.setString(2, t.getPassengerName());
                ps.setInt(3, t.getAge());
                ps.setString(4, t.getBusId());
                ps.setInt(5, t.getSeatNo());
                ps.setDouble(6, t.getFare());
                ps.setString(7, t.getBookedAt());
                ps.executeUpdate();
            }
        }
    }

    /* ---------- Local Database Mode ---------- */
    private static void initLocalDatabase() {
        localBuses.clear();
        localTickets.clear();

        // Standard seed routes
        addLocalBus(new Bus("B101", "Express Deluxe", "Coimbatore", "Chennai", "21:30", 20, 650.0));
        addLocalBus(new Bus("B102", "Night Rider", "Coimbatore", "Bangalore", "22:00", 16, 550.0));
        addLocalBus(new Bus("B103", "City Link", "Chennai", "Madurai", "20:15", 24, 480.0));
        addLocalBus(new Bus("B104", "Sleeper Plus", "Bangalore", "Hyderabad", "19:45", 12, 900.0));

        File bf = new File("buses.txt");
        if (bf.exists()) {
            try (BufferedReader br = new BufferedReader(new FileReader(bf))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (!line.trim().isEmpty()) {
                        try {
                            Bus b = Bus.fromRecordString(line);
                            localBuses.put(b.getBusId(), b);
                        } catch (Exception ignored) {}
                    }
                }
            } catch (Exception ignored) {}
        } else {
            saveLocalBuses();
        }

        File tf = new File("tickets.txt");
        if (tf.exists()) {
            try (BufferedReader br = new BufferedReader(new FileReader(tf))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (!line.trim().isEmpty()) {
                        try {
                            Ticket t = Ticket.fromRecordString(line);
                            Bus b = localBuses.get(t.getBusId());
                            if (b != null) {
                                b.bookSeat(t.getSeatNo());
                                localTickets.put(t.getTicketId(), t);
                                int num = Integer.parseInt(t.getTicketId().replaceAll("\\D", ""));
                                if (num >= nextTicketNo) nextTicketNo = num + 1;
                            }
                        } catch (Exception ignored) {}
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    private static void addLocalBus(Bus b) {
        localBuses.put(b.getBusId(), b);
    }

    private static void saveLocalBuses() {
        try (PrintWriter pw = new PrintWriter(new FileWriter("buses.txt"))) {
            for (Bus b : localBuses.values()) pw.println(b.toRecordString());
        } catch (Exception ignored) {}
    }

    private static void saveLocalTickets() {
        try (PrintWriter pw = new PrintWriter(new FileWriter("tickets.txt"))) {
            for (Ticket t : localTickets.values()) pw.println(t.toRecordString());
        } catch (Exception ignored) {}
    }

    /* ---------- CRUD Operations ---------- */
    public static List<Bus> getAllBuses() {
        if (useSqlite) {
            try (Connection conn = getConnection()) {
                List<Bus> list = new ArrayList<>();
                Map<String, Bus> map = new LinkedHashMap<>();
                String sql = "SELECT bus_id, name, source, destination, departure, total_seats, fare FROM buses ORDER BY bus_id";
                try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                    while (rs.next()) {
                        Bus b = new Bus(rs.getString("bus_id"), rs.getString("name"),
                                rs.getString("source"), rs.getString("destination"),
                                rs.getString("departure"), rs.getInt("total_seats"), rs.getDouble("fare"));
                        list.add(b);
                        map.put(b.getBusId(), b);
                    }
                }
                String seatSql = "SELECT bus_id, seat_no FROM tickets";
                try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(seatSql)) {
                    while (rs.next()) {
                        Bus b = map.get(rs.getString("bus_id"));
                        if (b != null) {
                            try { b.bookSeat(rs.getInt("seat_no")); } catch (Exception ignored) {}
                        }
                    }
                }
                return list;
            } catch (SQLException ex) {
                System.err.println("SQLite fetch error: " + ex.getMessage());
            }
        }
        return new ArrayList<>(localBuses.values());
    }

    public static Bus getBus(String busId) throws BusNotFoundException {
        if (busId == null) throw new BusNotFoundException("null");
        String id = busId.trim().toUpperCase();
        for (Bus b : getAllBuses()) {
            if (b.getBusId().equalsIgnoreCase(id)) return b;
        }
        throw new BusNotFoundException(busId);
    }

    public static List<Bus> searchBuses(String src, String dst) {
        List<Bus> all = getAllBuses();
        List<Bus> res = new ArrayList<>();
        String from = normalize(src);
        String to = normalize(dst);
        for (Bus b : all) {
            boolean fromOk = from.isEmpty() || normalize(b.getSource()).contains(from);
            boolean toOk = to.isEmpty() || normalize(b.getDestination()).contains(to);
            if (fromOk && toOk) res.add(b);
        }
        return res;
    }

    private static String normalize(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ").toLowerCase();
    }

    public static Ticket bookTicket(String busId, String name, int age, int seat)
            throws BusNotFoundException, SeatNotAvailableException, InvalidInputException, SQLException {
        if (name == null || name.trim().isEmpty() || name.contains(","))
            throw new InvalidInputException("Name cannot be empty or contain commas.");
        if (age < 1 || age > 120) throw new InvalidInputException("Age must be between 1 and 120.");

        Bus bus = getBus(busId);
        bus.bookSeat(seat);

        if (useSqlite) {
            try (Connection conn = getConnection()) {
                conn.setAutoCommit(false);
                try {
                    String checkSql = "SELECT COUNT(*) FROM tickets WHERE bus_id = ? AND seat_no = ?";
                    try (PreparedStatement ps = conn.prepareStatement(checkSql)) {
                        ps.setString(1, bus.getBusId());
                        ps.setInt(2, seat);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next() && rs.getInt(1) > 0) {
                                throw new SeatNotAvailableException("Seat " + seat + " is already booked in database.");
                            }
                        }
                    }

                    int nextNum = 1001;
                    try (Statement st = conn.createStatement();
                         ResultSet rs = st.executeQuery("SELECT ticket_id FROM tickets")) {
                        while (rs.next()) {
                            String tid = rs.getString(1);
                            if (tid != null && tid.startsWith("T")) {
                                try {
                                    int val = Integer.parseInt(tid.substring(1));
                                    if (val >= nextNum) nextNum = val + 1;
                                } catch (Exception ignored) {}
                            }
                        }
                    }

                    String ticketId = "T" + nextNum;
                    String bookedAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm"));

                    String insSql = "INSERT INTO tickets (ticket_id, passenger_name, age, bus_id, seat_no, fare, booked_at) VALUES (?, ?, ?, ?, ?, ?, ?)";
                    try (PreparedStatement ps = conn.prepareStatement(insSql)) {
                        ps.setString(1, ticketId);
                        ps.setString(2, name.trim());
                        ps.setInt(3, age);
                        ps.setString(4, bus.getBusId());
                        ps.setInt(5, seat);
                        ps.setDouble(6, bus.getFare());
                        ps.setString(7, bookedAt);
                        ps.executeUpdate();
                    }
                    conn.commit();
                    Ticket t = new Ticket(ticketId, name.trim(), age, bus.getBusId(), seat, bus.getFare(), bookedAt);
                    localTickets.put(ticketId, t);
                    return t;
                } catch (Exception ex) {
                    conn.rollback();
                    bus.releaseSeat(seat);
                    if (ex instanceof SeatNotAvailableException) throw (SeatNotAvailableException) ex;
                    throw new SQLException(ex.getMessage(), ex);
                } finally {
                    conn.setAutoCommit(true);
                }
            }
        }

        // Local mode
        String id = "T" + nextTicketNo++;
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm"));
        Ticket t = new Ticket(id, name.trim(), age, bus.getBusId(), seat, bus.getFare(), time);
        localTickets.put(id, t);
        saveLocalTickets();
        return t;
    }

    public static Ticket cancelTicket(String ticketId) throws TicketNotFoundException, SQLException {
        if (ticketId == null) throw new TicketNotFoundException("null");
        String id = ticketId.trim().toUpperCase();

        if (useSqlite) {
            try (Connection conn = getConnection()) {
                Ticket t = findTicket(id);
                String sql = "DELETE FROM tickets WHERE ticket_id = ?";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, id);
                    int r = ps.executeUpdate();
                    if (r == 0) throw new TicketNotFoundException(id);
                }
                localTickets.remove(id);
                Bus b = localBuses.get(t.getBusId());
                if (b != null) b.releaseSeat(t.getSeatNo());
                return t;
            }
        }

        Ticket t = localTickets.remove(id);
        if (t == null) throw new TicketNotFoundException(id);
        Bus b = localBuses.get(t.getBusId());
        if (b != null) b.releaseSeat(t.getSeatNo());
        saveLocalTickets();
        return t;
    }

    public static Ticket findTicket(String ticketId) throws TicketNotFoundException {
        if (ticketId == null) throw new TicketNotFoundException("null");
        String id = ticketId.trim().toUpperCase();

        if (useSqlite) {
            try (Connection conn = getConnection()) {
                String sql = "SELECT ticket_id, passenger_name, age, bus_id, seat_no, fare, booked_at FROM tickets WHERE ticket_id = ?";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, id);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            return new Ticket(rs.getString("ticket_id"), rs.getString("passenger_name"),
                                    rs.getInt("age"), rs.getString("bus_id"), rs.getInt("seat_no"),
                                    rs.getDouble("fare"), rs.getString("booked_at"));
                        }
                    }
                }
            } catch (SQLException ignored) {}
        }

        Ticket t = localTickets.get(id);
        if (t == null) throw new TicketNotFoundException(ticketId);
        return t;
    }

    public static List<Ticket> getAllTickets() {
        if (useSqlite) {
            try (Connection conn = getConnection()) {
                List<Ticket> list = new ArrayList<>();
                String sql = "SELECT ticket_id, passenger_name, age, bus_id, seat_no, fare, booked_at FROM tickets ORDER BY ticket_id ASC";
                try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                    while (rs.next()) {
                        list.add(new Ticket(rs.getString("ticket_id"), rs.getString("passenger_name"),
                                rs.getInt("age"), rs.getString("bus_id"), rs.getInt("seat_no"),
                                rs.getDouble("fare"), rs.getString("booked_at")));
                    }
                }
                return list;
            } catch (SQLException ignored) {}
        }
        return new ArrayList<>(localTickets.values());
    }

    public static void addBus(Bus bus) throws InvalidInputException, SQLException {
        if (bus.getBusId() == null || bus.getBusId().trim().isEmpty())
            throw new InvalidInputException("Bus ID cannot be empty.");
        if (bus.getName() == null || bus.getName().trim().isEmpty())
            throw new InvalidInputException("Bus Name cannot be empty.");
        if (bus.getSource() == null || bus.getSource().trim().isEmpty())
            throw new InvalidInputException("Source city cannot be empty.");
        if (bus.getDestination() == null || bus.getDestination().trim().isEmpty())
            throw new InvalidInputException("Destination city cannot be empty.");
        if (bus.getTotalSeats() <= 0)
            throw new InvalidInputException("Total seats must be greater than 0.");
        if (bus.getFare() < 0)
            throw new InvalidInputException("Fare cannot be negative.");

        if (useSqlite) {
            try (Connection conn = getConnection()) {
                String sql = "INSERT INTO buses (bus_id, name, source, destination, departure, total_seats, fare) VALUES (?, ?, ?, ?, ?, ?, ?)";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, bus.getBusId().trim().toUpperCase());
                    ps.setString(2, bus.getName().trim());
                    ps.setString(3, bus.getSource().trim());
                    ps.setString(4, bus.getDestination().trim());
                    ps.setString(5, bus.getDeparture().trim());
                    ps.setInt(6, bus.getTotalSeats());
                    ps.setDouble(7, bus.getFare());
                    ps.executeUpdate();
                }
            }
        }
        localBuses.put(bus.getBusId().toUpperCase(), bus);
        saveLocalBuses();
    }

    public static void deleteBus(String busId) throws SQLException, BusNotFoundException {
        if (busId == null) throw new BusNotFoundException("null");
        String id = busId.trim().toUpperCase();

        if (useSqlite) {
            try (Connection conn = getConnection()) {
                String sql = "DELETE FROM buses WHERE bus_id = ?";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, id);
                    int r = ps.executeUpdate();
                    if (r == 0) throw new BusNotFoundException(id);
                }
            }
        }
        if (!localBuses.containsKey(id)) throw new BusNotFoundException(id);
        localBuses.remove(id);
        saveLocalBuses();
    }

    public static void resetSampleData() throws SQLException {
        if (useSqlite) {
            try (Connection conn = getConnection()) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("DELETE FROM tickets;");
                    stmt.execute("DELETE FROM buses;");
                    seedDefaultBusesToSqlite(conn);
                }
            }
        }
        initLocalDatabase();
    }

    public static TableModel executeCustomSql(String sql) throws SQLException {
        if (!useSqlite) {
            throw new SQLException("SQL console requires SQLite JDBC connection.");
        }
        try (Connection conn = getConnection();
             Statement st = conn.createStatement()) {
            String trimmed = sql.trim().toLowerCase();
            if (trimmed.startsWith("select") || trimmed.startsWith("pragma") || trimmed.startsWith("explain")) {
                try (ResultSet rs = st.executeQuery(sql)) {
                    ResultSetMetaData meta = rs.getMetaData();
                    int count = meta.getColumnCount();
                    Vector<String> cols = new Vector<>();
                    for (int i = 1; i <= count; i++) cols.add(meta.getColumnLabel(i));
                    Vector<Vector<Object>> rows = new Vector<>();
                    while (rs.next()) {
                        Vector<Object> r = new Vector<>();
                        for (int i = 1; i <= count; i++) r.add(rs.getObject(i));
                        rows.add(r);
                    }
                    return new DefaultTableModel(rows, cols) {
                        @Override public boolean isCellEditable(int r, int c) { return false; }
                    };
                }
            } else {
                int affected = st.executeUpdate(sql);
                Vector<String> cols = new Vector<>(Collections.singletonList("Execution Result"));
                Vector<Vector<Object>> rows = new Vector<>();
                rows.add(new Vector<>(Collections.singletonList("Success: " + affected + " row(s) affected.")));
                return new DefaultTableModel(rows, cols);
            }
        }
    }

    public static Map<String, Object> getDatabaseStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        List<Bus> buses = getAllBuses();
        List<Ticket> tickets = getAllTickets();

        double totalRevenue = 0.0;
        for (Ticket t : tickets) totalRevenue += t.getFare();

        stats.put("total_buses", buses.size());
        stats.put("total_tickets", tickets.size());
        stats.put("total_revenue", totalRevenue);
        stats.put("db_mode", useSqlite ? "SQLite 3 (" + DB_FILE + ")" : "Local Resilient Store");
        return stats;
    }
}

/* ===================== BUSINESS LOGIC ===================== */
class BookingService {
    public void loadData() { DatabaseManager.initDatabase(); }
    public List<Bus> getAllBuses() { return DatabaseManager.getAllBuses(); }
    public List<Ticket> getAllTickets() { return DatabaseManager.getAllTickets(); }
    public Bus getBus(String busId) throws BusNotFoundException { return DatabaseManager.getBus(busId); }
    public List<Bus> searchBuses(String src, String dst) { return DatabaseManager.searchBuses(src, dst); }
    public Ticket bookTicket(String busId, String name, int age, int seat)
            throws BusNotFoundException, SeatNotAvailableException, InvalidInputException, SQLException {
        return DatabaseManager.bookTicket(busId, name, age, seat);
    }
    public Ticket cancelTicket(String ticketId) throws TicketNotFoundException, SQLException {
        return DatabaseManager.cancelTicket(ticketId);
    }
    public Ticket findTicket(String ticketId) throws TicketNotFoundException { return DatabaseManager.findTicket(ticketId); }
    public void addBus(Bus bus) throws SQLException, InvalidInputException { DatabaseManager.addBus(bus); }
    public void deleteBus(String busId) throws SQLException, BusNotFoundException { DatabaseManager.deleteBus(busId); }
    public void resetData() throws SQLException { DatabaseManager.resetSampleData(); }
    public Map<String, Object> getStats() { return DatabaseManager.getDatabaseStats(); }
    public boolean isSqliteActive() { return DatabaseManager.isUsingSqlite(); }
    public void connectSqlite() throws Exception { DatabaseManager.downloadAndConnectSqlite(); }
    public TableModel runSql(String sql) throws SQLException { return DatabaseManager.executeCustomSql(sql); }
}

/* ===================== GUI ===================== */
public class BusReservationGUI extends JFrame {

    // Modern color palette
    private static final Color PRIMARY = new Color(37, 99, 235);       // Royal Blue
    private static final Color SUCCESS = new Color(22, 163, 74);        // Emerald Green
    private static final Color DANGER = new Color(220, 38, 38);         // Crimson Red
    private static final Color WARNING = new Color(217, 119, 6);        // Amber Orange
    private static final Color BG_LIGHT = new Color(248, 250, 252);     // Slate 50
    private static final Color TEXT_DARK = new Color(15, 23, 42);       // Slate 900
    private static final Color TEXT_MUTED = new Color(100, 116, 139);   // Slate 500
    private static final Color BORDER_COLOR = new Color(226, 232, 240); // Slate 200

    private static final Color COLOR_AVAILABLE = new Color(34, 197, 94);  // Green
    private static final Color COLOR_BOOKED = new Color(239, 68, 68);     // Red
    private static final Color COLOR_SELECTED = new Color(245, 158, 11);  // Amber

    private final BookingService service = new BookingService();

    // Tab 1: Search & Book
    private final JTextField fromField = new JTextField(12);
    private final JTextField toField = new JTextField(12);
    private final DefaultTableModel busModel = new DefaultTableModel(
            new String[]{"Bus ID", "Service Name", "Source", "Destination", "Departure", "Fare (Rs.)", "Seats Available"}, 0) {
        @Override public boolean isCellEditable(int r, int c) { return false; }
    };
    private final JTable busTable = new JTable(busModel);
    private final JPanel seatGridPanel = new JPanel();
    private final JLabel seatTitleLabel = new JLabel("Please select a bus from the table above");
    private final JLabel selectedSeatText = new JLabel("Selected seat: None");
    private final JTextField passengerNameField = new JTextField(15);
    private final JTextField passengerAgeField = new JTextField(15);

    // Tab 2: Bookings Table
    private final DefaultTableModel ticketModel = new DefaultTableModel(
            new String[]{"Ticket ID", "Passenger Name", "Age", "Bus ID", "Seat No", "Fare Paid (Rs.)", "Booked Timestamp"}, 0) {
        @Override public boolean isCellEditable(int r, int c) { return false; }
    };
    private final JTable ticketTable = new JTable(ticketModel);

    // Tab 3: Admin & Database Live Viewers
    private final JLabel statDbModeBadge = new JLabel("SQLite 3");
    private final JLabel statBusesVal = new JLabel("0");
    private final JLabel statTicketsVal = new JLabel("0");
    private final JLabel statRevenueVal = new JLabel("Rs. 0.00");

    private final DefaultTableModel adminBusModel = new DefaultTableModel(
            new String[]{"Bus ID", "Name", "From", "To", "Departure", "Total Seats", "Fare (Rs.)"}, 0) {
        @Override public boolean isCellEditable(int r, int c) { return false; }
    };
    private final JTable adminBusTable = new JTable(adminBusModel);

    private final DefaultTableModel adminTicketModel = new DefaultTableModel(
            new String[]{"Ticket ID", "Passenger", "Age", "Bus ID", "Seat No", "Fare (Rs.)", "Booked At"}, 0) {
        @Override public boolean isCellEditable(int r, int c) { return false; }
    };
    private final JTable adminTicketTable = new JTable(adminTicketModel);

    private final JTextArea sqlInputArea = new JTextArea("SELECT * FROM buses;");
    private final JTable sqlResultTable = new JTable();

    private final JLabel statusLabel = new JLabel(" Ready");

    private List<Bus> currentList = new ArrayList<>();
    private Bus selectedBus = null;
    private int selectedSeat = -1;
    private boolean updating = false;

    public BusReservationGUI() {
        super("SwiftTransit - Modern Bus Ticket Reservation System (SQLite Database Edition)");

        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1060, 740);
        setMinimumSize(new Dimension(960, 680));
        setLocationRelativeTo(null);

        // Load data
        service.loadData();
        currentList = service.getAllBuses();

        // Main layout
        JPanel contentPane = new JPanel(new BorderLayout(0, 0));
        contentPane.setBackground(BG_LIGHT);
        setContentPane(contentPane);

        // Header Banner
        contentPane.add(createAppHeader(), BorderLayout.NORTH);

        // Tabs
        JTabbedPane mainTabs = new JTabbedPane();
        mainTabs.setFont(new Font("Segoe UI", Font.BOLD, 13));
        mainTabs.addTab("  Search & Book Tickets  ", buildBookingTab());
        mainTabs.addTab("  My Confirmed Bookings  ", buildBookingsTab());
        mainTabs.addTab("  Database & Administration  ", buildAdminTab());
        contentPane.add(mainTabs, BorderLayout.CENTER);

        // Status bar
        JPanel statusBar = new JPanel(new BorderLayout());
        statusBar.setBackground(Color.WHITE);
        statusBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER_COLOR),
                new EmptyBorder(6, 12, 6, 12)
        ));
        statusLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        statusLabel.setForeground(TEXT_DARK);
        statusBar.add(statusLabel, BorderLayout.WEST);

        JLabel dbIndicator = new JLabel("● Database Engine: " + (service.isSqliteActive() ? "SQLite 3 (" + DatabaseManager.DB_FILE + ")" : "Local Resilient Store") + "  ");
        dbIndicator.setFont(new Font("Segoe UI", Font.BOLD, 12));
        dbIndicator.setForeground(service.isSqliteActive() ? SUCCESS : WARNING);
        statusBar.add(dbIndicator, BorderLayout.EAST);

        contentPane.add(statusBar, BorderLayout.SOUTH);

        // Format tables
        styleTable(busTable);
        styleTable(ticketTable);
        styleTable(adminBusTable);
        styleTable(adminTicketTable);
        styleTable(sqlResultTable);

        // Populate initial data
        refreshBusTable();
        refreshTicketTable();
        refreshAdminTables();
        refreshStats();
        setStatus("Ready. Connected to " + (service.isSqliteActive() ? "SQLite 3 Database (" + DatabaseManager.DB_FILE + ")" : "Local Database"));
    }

    private JPanel createAppHeader() {
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(PRIMARY);
        header.setBorder(new EmptyBorder(12, 16, 12, 16));

        JLabel title = new JLabel("SwiftTransit \u2014 Next-Gen Bus Reservation System");
        title.setFont(new Font("Segoe UI", Font.BOLD, 18));
        title.setForeground(Color.WHITE);

        JLabel subtitle = new JLabel("SQLite Relational Database Edition  |  Live Seat Allocation & Fleet Management");
        subtitle.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        subtitle.setForeground(new Color(224, 231, 255));

        JPanel textWrap = new JPanel(new GridLayout(2, 1, 2, 2));
        textWrap.setOpaque(false);
        textWrap.add(title);
        textWrap.add(subtitle);

        header.add(textWrap, BorderLayout.WEST);
        return header;
    }

    /* ===================== TAB 1: SEARCH & BOOK ===================== */
    private JPanel buildBookingTab() {
        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBackground(BG_LIGHT);
        root.setBorder(new EmptyBorder(12, 14, 12, 14));

        // 1. Search Bar
        JPanel searchBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 6));
        searchBar.setBackground(Color.WHITE);
        searchBar.setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(BORDER_COLOR, 1, true),
                new EmptyBorder(6, 10, 6, 10)
        ));

        JLabel fromLbl = new JLabel("From:");
        fromLbl.setFont(new Font("Segoe UI", Font.BOLD, 12));
        JLabel toLbl = new JLabel("To:");
        toLbl.setFont(new Font("Segoe UI", Font.BOLD, 12));

        JButton searchBtn = createButton("Search Routes", PRIMARY, Color.WHITE);
        JButton showAllBtn = createButton("Show All Buses", BG_LIGHT, TEXT_DARK);
        showAllBtn.setBorder(new LineBorder(BORDER_COLOR, 1, true));

        searchBar.add(fromLbl);
        searchBar.add(fromField);
        searchBar.add(toLbl);
        searchBar.add(toField);
        searchBar.add(searchBtn);
        searchBar.add(showAllBtn);

        JLabel tip = new JLabel("  (Leave fields blank to match all destinations)");
        tip.setFont(new Font("Segoe UI", Font.ITALIC, 11));
        tip.setForeground(TEXT_MUTED);
        searchBar.add(tip);

        root.add(searchBar, BorderLayout.NORTH);

        // 2. Center: Buses Table
        JPanel tableWrap = new JPanel(new BorderLayout(4, 4));
        tableWrap.setBackground(Color.WHITE);
        tableWrap.setBorder(BorderFactory.createTitledBorder(new LineBorder(BORDER_COLOR, 1, true), " Available Bus Routes (Select a row to view seats) "));
        busTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        busTable.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || updating) return;
            int r = busTable.getSelectedRow();
            selectedBus = (r >= 0 && r < currentList.size()) ? currentList.get(r) : null;
            selectedSeat = -1;
            drawSeats();
        });
        tableWrap.add(new JScrollPane(busTable), BorderLayout.CENTER);
        root.add(tableWrap, BorderLayout.CENTER);

        // 3. Bottom: Seat Map (Left) + Booking Form (Right)
        JPanel bottomSplit = new JPanel(new BorderLayout(12, 0));
        bottomSplit.setOpaque(false);
        bottomSplit.setPreferredSize(new Dimension(0, 270));

        // Seat Map Box
        JPanel seatBox = new JPanel(new BorderLayout(6, 6));
        seatBox.setBackground(Color.WHITE);
        seatBox.setBorder(BorderFactory.createTitledBorder(new LineBorder(BORDER_COLOR, 1, true), " Interactive Seat Map "));

        JPanel seatHeader = new JPanel(new BorderLayout(6, 4));
        seatHeader.setOpaque(false);
        seatHeader.setBorder(new EmptyBorder(4, 8, 4, 8));
        seatTitleLabel.setFont(new Font("Segoe UI", Font.BOLD, 13));
        seatTitleLabel.setForeground(TEXT_DARK);
        seatHeader.add(seatTitleLabel, BorderLayout.NORTH);
        seatHeader.add(createSeatLegend(), BorderLayout.SOUTH);
        seatBox.add(seatHeader, BorderLayout.NORTH);

        JPanel scrollHolder = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 10));
        scrollHolder.setBackground(Color.WHITE);
        seatGridPanel.setOpaque(false);
        scrollHolder.add(seatGridPanel);
        JScrollPane seatScroll = new JScrollPane(scrollHolder);
        seatScroll.setBorder(null);
        seatBox.add(seatScroll, BorderLayout.CENTER);

        bottomSplit.add(seatBox, BorderLayout.CENTER);

        // Passenger Form Box
        JPanel formBox = new JPanel(new BorderLayout());
        formBox.setBackground(Color.WHITE);
        formBox.setPreferredSize(new Dimension(330, 0));
        formBox.setBorder(BorderFactory.createTitledBorder(new LineBorder(BORDER_COLOR, 1, true), " Passenger & Booking Details "));

        JPanel formGrid = new JPanel(new GridBagLayout());
        formGrid.setOpaque(false);
        formGrid.setBorder(new EmptyBorder(10, 14, 10, 14));
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(6, 4, 6, 4);
        g.fill = GridBagConstraints.HORIZONTAL;
        g.anchor = GridBagConstraints.WEST;

        g.gridx = 0; g.gridy = 0; formGrid.add(new JLabel("Passenger Name:"), g);
        g.gridx = 1; formGrid.add(passengerNameField, g);

        g.gridx = 0; g.gridy = 1; formGrid.add(new JLabel("Passenger Age:"), g);
        g.gridx = 1; formGrid.add(passengerAgeField, g);

        g.gridx = 0; g.gridy = 2; g.gridwidth = 2;
        selectedSeatText.setFont(new Font("Segoe UI", Font.BOLD, 13));
        selectedSeatText.setForeground(PRIMARY);
        formGrid.add(selectedSeatText, g);

        JButton bookBtn = createButton("Confirm & Book Ticket", SUCCESS, Color.WHITE);
        bookBtn.setFont(new Font("Segoe UI", Font.BOLD, 13));
        g.gridy = 3; formGrid.add(bookBtn, g);

        formBox.add(formGrid, BorderLayout.CENTER);
        bottomSplit.add(formBox, BorderLayout.EAST);

        root.add(bottomSplit, BorderLayout.SOUTH);

        // Handlers
        searchBtn.addActionListener(e -> doSearch());
        showAllBtn.addActionListener(e -> {
            fromField.setText(""); toField.setText("");
            reloadAllBuses();
            setStatus("Displaying all available routes.");
        });
        bookBtn.addActionListener(e -> doBook());

        return root;
    }

    private JPanel createSeatLegend() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 14, 2));
        p.setOpaque(false);
        p.add(legendDot(COLOR_AVAILABLE, "Available"));
        p.add(legendDot(COLOR_BOOKED, "Booked in DB"));
        p.add(legendDot(COLOR_SELECTED, "Selected Seat"));
        return p;
    }

    private JLabel legendDot(Color c, String text) {
        JLabel l = new JLabel(" " + text);
        l.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        l.setIcon(new Icon() {
            public void paintIcon(Component comp, Graphics gr, int x, int y) {
                Graphics2D g2 = (Graphics2D) gr.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(c);
                g2.fillRoundRect(x, y, 14, 14, 4, 4);
                g2.dispose();
            }
            public int getIconWidth() { return 14; }
            public int getIconHeight() { return 14; }
        });
        return l;
    }

    /* ===================== TAB 2: MY BOOKINGS ===================== */
    private JPanel buildBookingsTab() {
        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBackground(BG_LIGHT);
        root.setBorder(new EmptyBorder(12, 14, 12, 14));

        // Toolbar
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 6));
        toolbar.setBackground(Color.WHITE);
        toolbar.setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(BORDER_COLOR, 1, true),
                new EmptyBorder(6, 10, 6, 10)
        ));

        JButton viewBtn = createButton("View Receipt", PRIMARY, Color.WHITE);
        JButton cancelBtn = createButton("Cancel Booking", DANGER, Color.WHITE);
        JButton findBtn = createButton("Find by ID", BG_LIGHT, TEXT_DARK);
        findBtn.setBorder(new LineBorder(BORDER_COLOR, 1, true));
        JButton refreshBtn = createButton("Refresh Table", BG_LIGHT, TEXT_DARK);
        refreshBtn.setBorder(new LineBorder(BORDER_COLOR, 1, true));

        toolbar.add(viewBtn);
        toolbar.add(cancelBtn);
        toolbar.add(findBtn);
        toolbar.add(refreshBtn);
        root.add(toolbar, BorderLayout.NORTH);

        // Tickets table
        JPanel tableWrap = new JPanel(new BorderLayout());
        tableWrap.setBackground(Color.WHITE);
        tableWrap.setBorder(BorderFactory.createTitledBorder(new LineBorder(BORDER_COLOR, 1, true), " Confirmed Bookings Stored in Database "));
        ticketTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        tableWrap.add(new JScrollPane(ticketTable), BorderLayout.CENTER);
        root.add(tableWrap, BorderLayout.CENTER);

        // Actions
        viewBtn.addActionListener(e -> {
            String id = selectedTicketId();
            if (id == null) return;
            try { showTicketDialog(service.findTicket(id)); }
            catch (TicketNotFoundException ex) { showError(ex.getMessage()); }
        });
        cancelBtn.addActionListener(e -> doCancel());
        findBtn.addActionListener(e -> {
            String id = JOptionPane.showInputDialog(this, "Enter Ticket ID (e.g. T1001):", "Find Ticket", JOptionPane.QUESTION_MESSAGE);
            if (id == null || id.trim().isEmpty()) return;
            try { showTicketDialog(service.findTicket(id.trim())); }
            catch (TicketNotFoundException ex) { showError(ex.getMessage()); }
        });
        refreshBtn.addActionListener(e -> {
            refreshTicketTable();
            reloadAllBuses();
            refreshStats();
            setStatus("Refreshed bookings from database.");
        });

        return root;
    }

    /* ===================== TAB 3: DATABASE & ADMIN ===================== */
    private JPanel buildAdminTab() {
        JPanel root = new JPanel(new BorderLayout(12, 12));
        root.setBackground(BG_LIGHT);
        root.setBorder(new EmptyBorder(14, 14, 14, 14));

        // 1. Metric Cards Header
        JPanel metricsPanel = new JPanel(new GridLayout(1, 4, 12, 12));
        metricsPanel.setOpaque(false);
        metricsPanel.add(createMetricCard("Database Engine", statDbModeBadge, "SQLite 3", PRIMARY));
        metricsPanel.add(createMetricCard("Total Bus Routes", statBusesVal, "Active Routes", SUCCESS));
        metricsPanel.add(createMetricCard("Total Bookings", statTicketsVal, "Confirmed Tickets", new Color(147, 51, 234)));
        metricsPanel.add(createMetricCard("Total Revenue", statRevenueVal, "Gross Collection", WARNING));
        root.add(metricsPanel, BorderLayout.NORTH);

        // 2. Database Explorer Tabs
        JTabbedPane dbTabs = new JTabbedPane();
        dbTabs.setFont(new Font("Segoe UI", Font.PLAIN, 12));

        // Sub-Tab 1: buses Table
        JPanel busesTab = new JPanel(new BorderLayout(8, 8));
        busesTab.setBackground(Color.WHITE);
        busesTab.setBorder(new EmptyBorder(8, 8, 8, 8));

        JPanel busActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        busActions.setOpaque(false);
        JButton addBusBtn = createButton("+ Add New Bus Route", PRIMARY, Color.WHITE);
        JButton delBusBtn = createButton("Delete Selected Bus", DANGER, Color.WHITE);
        JButton refreshBusesBtn = createButton("Refresh", BG_LIGHT, TEXT_DARK);
        refreshBusesBtn.setBorder(new LineBorder(BORDER_COLOR, 1, true));
        JButton resetSampleBtn = createButton("Reset Sample Data", BG_LIGHT, TEXT_DARK);
        resetSampleBtn.setBorder(new LineBorder(BORDER_COLOR, 1, true));

        busActions.add(addBusBtn);
        busActions.add(delBusBtn);
        busActions.add(refreshBusesBtn);
        busActions.add(resetSampleBtn);
        busesTab.add(busActions, BorderLayout.NORTH);
        busesTab.add(new JScrollPane(adminBusTable), BorderLayout.CENTER);
        dbTabs.addTab("  Table: buses  ", busesTab);

        // Sub-Tab 2: tickets Table
        JPanel ticketsTab = new JPanel(new BorderLayout(8, 8));
        ticketsTab.setBackground(Color.WHITE);
        ticketsTab.setBorder(new EmptyBorder(8, 8, 8, 8));

        JPanel ticketActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        ticketActions.setOpaque(false);
        JButton viewTicketAdminBtn = createButton("View Selected Receipt", PRIMARY, Color.WHITE);
        JButton cancelTicketAdminBtn = createButton("Cancel Selected Booking", DANGER, Color.WHITE);
        JButton refreshTicketsAdminBtn = createButton("Refresh", BG_LIGHT, TEXT_DARK);
        refreshTicketsAdminBtn.setBorder(new LineBorder(BORDER_COLOR, 1, true));

        ticketActions.add(viewTicketAdminBtn);
        ticketActions.add(cancelTicketAdminBtn);
        ticketActions.add(refreshTicketsAdminBtn);
        ticketsTab.add(ticketActions, BorderLayout.NORTH);
        ticketsTab.add(new JScrollPane(adminTicketTable), BorderLayout.CENTER);
        dbTabs.addTab("  Table: tickets  ", ticketsTab);

        // Sub-Tab 3: SQL Console (Interactive WOW factor!)
        JPanel sqlTab = new JPanel(new BorderLayout(8, 8));
        sqlTab.setBackground(Color.WHITE);
        sqlTab.setBorder(new EmptyBorder(10, 10, 10, 10));

        JPanel sqlTop = new JPanel(new BorderLayout(6, 6));
        sqlTop.setOpaque(false);

        JPanel presets = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        presets.setOpaque(false);
        presets.add(new JLabel("Quick Queries:"));
        JButton q1 = createPresetBtn("SELECT * FROM buses;");
        JButton q2 = createPresetBtn("SELECT * FROM tickets;");
        JButton q3 = createPresetBtn("SELECT bus_id, COUNT(*) AS total_booked, SUM(fare) AS total_revenue FROM tickets GROUP BY bus_id;");
        presets.add(q1); presets.add(q2); presets.add(q3);
        sqlTop.add(presets, BorderLayout.NORTH);

        sqlInputArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        sqlInputArea.setRows(3);
        sqlInputArea.setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(BORDER_COLOR, 1),
                new EmptyBorder(6, 6, 6, 6)
        ));
        sqlTop.add(new JScrollPane(sqlInputArea), BorderLayout.CENTER);

        JButton executeSqlBtn = createButton("Execute Query", PRIMARY, Color.WHITE);
        executeSqlBtn.setFont(new Font("Segoe UI", Font.BOLD, 12));
        JPanel execWrap = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        execWrap.setOpaque(false);
        execWrap.add(executeSqlBtn);
        sqlTop.add(execWrap, BorderLayout.SOUTH);

        sqlTab.add(sqlTop, BorderLayout.NORTH);
        sqlTab.add(new JScrollPane(sqlResultTable), BorderLayout.CENTER);
        dbTabs.addTab("  Interactive SQL Console  ", sqlTab);

        root.add(dbTabs, BorderLayout.CENTER);

        // Event Listeners for Admin Tab
        addBusBtn.addActionListener(e -> showAddBusDialog());
        delBusBtn.addActionListener(e -> doDeleteSelectedBus());
        refreshBusesBtn.addActionListener(e -> { refreshAdminTables(); setStatus("Refreshed buses table."); });
        resetSampleBtn.addActionListener(e -> doResetSampleData());

        viewTicketAdminBtn.addActionListener(e -> {
            int r = adminTicketTable.getSelectedRow();
            if (r < 0) { showError("Please select a ticket row first."); return; }
            String id = String.valueOf(adminTicketTable.getValueAt(r, 0));
            try { showTicketDialog(service.findTicket(id)); }
            catch (TicketNotFoundException ex) { showError(ex.getMessage()); }
        });
        cancelTicketAdminBtn.addActionListener(e -> {
            int r = adminTicketTable.getSelectedRow();
            if (r < 0) { showError("Please select a ticket row first."); return; }
            String id = String.valueOf(adminTicketTable.getValueAt(r, 0));
            int ok = JOptionPane.showConfirmDialog(this, "Cancel ticket " + id + "?", "Confirm Cancellation", JOptionPane.YES_NO_OPTION);
            if (ok == JOptionPane.YES_OPTION) {
                try {
                    service.cancelTicket(id);
                    refreshAdminTables();
                    refreshTicketTable();
                    reloadAllBuses();
                    refreshStats();
                    setStatus("Cancelled ticket " + id);
                } catch (Exception ex) { showError(ex.getMessage()); }
            }
        });
        refreshTicketsAdminBtn.addActionListener(e -> { refreshAdminTables(); setStatus("Refreshed tickets table."); });

        executeSqlBtn.addActionListener(e -> {
            String sql = sqlInputArea.getText().trim();
            if (sql.isEmpty()) return;
            try {
                TableModel res = service.runSql(sql);
                sqlResultTable.setModel(res);
                setStatus("Query executed successfully.");
            } catch (SQLException ex) {
                showError("SQL Execution Error:\n" + ex.getMessage());
            }
        });

        return root;
    }

    private JButton createPresetBtn(String query) {
        JButton b = new JButton(query.length() > 32 ? query.substring(0, 32) + "..." : query);
        b.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        b.setToolTipText(query);
        b.setBackground(BG_LIGHT);
        b.addActionListener(e -> sqlInputArea.setText(query));
        return b;
    }

    private JPanel createMetricCard(String title, JLabel valLabel, String subtext, Color accent) {
        JPanel card = new JPanel(new BorderLayout(4, 4));
        card.setBackground(Color.WHITE);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 4, 0, 0, accent),
                BorderFactory.createCompoundBorder(
                        new LineBorder(BORDER_COLOR, 1, false),
                        new EmptyBorder(10, 14, 10, 14)
                )
        ));

        JLabel t = new JLabel(title);
        t.setFont(new Font("Segoe UI", Font.BOLD, 12));
        t.setForeground(TEXT_MUTED);

        valLabel.setFont(new Font("Segoe UI", Font.BOLD, 18));
        valLabel.setForeground(accent);

        JLabel sub = new JLabel(subtext);
        sub.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        sub.setForeground(TEXT_MUTED);

        card.add(t, BorderLayout.NORTH);
        card.add(valLabel, BorderLayout.CENTER);
        card.add(sub, BorderLayout.SOUTH);
        return card;
    }

    /* ===================== LOGIC & ACTIONS ===================== */
    private void doSearch() {
        currentList = service.searchBuses(fromField.getText(), toField.getText());
        refreshBusTable();
        if (currentList.isEmpty()) setStatus("No buses found for the given route.");
        else setStatus(currentList.size() + " bus(es) matched.");
    }

    private void reloadAllBuses() {
        currentList = service.getAllBuses();
        refreshBusTable();
    }

    private void doBook() {
        if (selectedBus == null) { showError("Please select a bus from the table first."); return; }
        if (selectedSeat == -1) { showError("Please click a green seat to choose it."); return; }

        String name = passengerNameField.getText().trim();
        if (name.isEmpty()) { showError("Please enter the passenger's name."); return; }

        int age;
        try {
            age = Integer.parseInt(passengerAgeField.getText().trim());
        } catch (NumberFormatException ex) {
            showError("Passenger age must be a valid integer number.");
            return;
        }

        try {
            Ticket t = service.bookTicket(selectedBus.getBusId(), name, age, selectedSeat);
            passengerNameField.setText("");
            passengerAgeField.setText("");
            selectedSeat = -1;
            reloadAllBuses();
            refreshTicketTable();
            refreshAdminTables();
            refreshStats();
            setStatus("Successfully booked ticket " + t.getTicketId() + " (Seat " + t.getSeatNo() + ").");
            showTicketDialog(t);
        } catch (InvalidInputException | BusNotFoundException | SeatNotAvailableException ex) {
            showError(ex.getMessage());
            reloadAllBuses();
        } catch (SQLException ex) {
            showError("Database Error:\n" + ex.getMessage());
            reloadAllBuses();
        }
    }

    private void doCancel() {
        String id = selectedTicketId();
        if (id == null) return;
        int ok = JOptionPane.showConfirmDialog(this, "Are you sure you want to cancel ticket " + id + "?",
                "Confirm Cancellation", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (ok != JOptionPane.YES_OPTION) return;
        try {
            Ticket t = service.cancelTicket(id);
            refreshTicketTable();
            reloadAllBuses();
            refreshAdminTables();
            refreshStats();
            setStatus("Cancelled ticket " + t.getTicketId() + ". Seat #" + t.getSeatNo() + " is now free.");
        } catch (TicketNotFoundException ex) {
            showError(ex.getMessage());
        } catch (SQLException ex) {
            showError("Database Error:\n" + ex.getMessage());
        }
    }

    private void doDeleteSelectedBus() {
        int r = adminBusTable.getSelectedRow();
        if (r < 0) { showError("Please select a bus to delete."); return; }
        String busId = String.valueOf(adminBusTable.getValueAt(r, 0));
        int ok = JOptionPane.showConfirmDialog(this,
                "Delete bus " + busId + "?\nAll associated tickets will also be deleted from the database.",
                "Confirm Delete", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (ok == JOptionPane.YES_OPTION) {
            try {
                service.deleteBus(busId);
                reloadAllBuses();
                refreshTicketTable();
                refreshAdminTables();
                refreshStats();
                setStatus("Bus " + busId + " deleted from database.");
            } catch (Exception ex) {
                showError("Delete failed: " + ex.getMessage());
            }
        }
    }

    private void doResetSampleData() {
        int ok = JOptionPane.showConfirmDialog(this,
                "Reset all data in database to default sample buses?\nAll existing bookings will be cleared.",
                "Confirm Reset", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (ok == JOptionPane.YES_OPTION) {
            try {
                service.resetData();
                reloadAllBuses();
                refreshTicketTable();
                refreshAdminTables();
                refreshStats();
                setStatus("Database reset to sample state.");
                JOptionPane.showMessageDialog(this, "Sample data restored successfully!", "Success", JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception ex) {
                showError("Reset error: " + ex.getMessage());
            }
        }
    }

    private void showAddBusDialog() {
        JTextField idField = new JTextField();
        JTextField nameField = new JTextField();
        JTextField srcField = new JTextField();
        JTextField dstField = new JTextField();
        JTextField depField = new JTextField("20:00");
        JTextField seatsField = new JTextField("20");
        JTextField fareField = new JTextField("500");

        JPanel form = new JPanel(new GridLayout(7, 2, 6, 8));
        form.add(new JLabel("Bus ID (e.g. B105):")); form.add(idField);
        form.add(new JLabel("Service Name:")); form.add(nameField);
        form.add(new JLabel("Source City:")); form.add(srcField);
        form.add(new JLabel("Destination City:")); form.add(dstField);
        form.add(new JLabel("Departure Time:")); form.add(depField);
        form.add(new JLabel("Total Seats:")); form.add(seatsField);
        form.add(new JLabel("Fare (Rs.):")); form.add(fareField);

        int res = JOptionPane.showConfirmDialog(this, form, "Add New Bus to SQLite",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (res == JOptionPane.OK_OPTION) {
            try {
                int seats = Integer.parseInt(seatsField.getText().trim());
                double fare = Double.parseDouble(fareField.getText().trim());
                Bus b = new Bus(idField.getText().trim(), nameField.getText().trim(),
                        srcField.getText().trim(), dstField.getText().trim(),
                        depField.getText().trim(), seats, fare);
                service.addBus(b);
                reloadAllBuses();
                refreshAdminTables();
                refreshStats();
                setStatus("Bus " + b.getBusId() + " successfully inserted into database.");
                JOptionPane.showMessageDialog(this, "Bus added to SQLite database!", "Success", JOptionPane.INFORMATION_MESSAGE);
            } catch (NumberFormatException ex) {
                showError("Seats and Fare must be valid numbers.");
            } catch (InvalidInputException | SQLException ex) {
                showError("Error adding bus: " + ex.getMessage());
            }
        }
    }

    /* ===================== SEAT MAP DRAWING ===================== */
    private void drawSeats() {
        seatGridPanel.removeAll();
        if (selectedBus == null) {
            seatTitleLabel.setText("Please select a bus from the table above");
            selectedSeatText.setText("Selected seat: None");
        } else {
            seatTitleLabel.setText(selectedBus.getBusId() + " - " + selectedBus.getName() + "  ("
                    + selectedBus.getSource() + " \u2192 " + selectedBus.getDestination() + ")  |  Departure: " + selectedBus.getDeparture());

            int total = selectedBus.getTotalSeats();
            int rows = (int) Math.ceil(total / 4.0);

            seatGridPanel.setLayout(new GridLayout(rows + 1, 5, 8, 8));

            // Front indicator
            JLabel frontL = new JLabel("<< FRONT / DRIVER", SwingConstants.CENTER);
            frontL.setFont(new Font("Segoe UI", Font.BOLD, 11));
            frontL.setForeground(TEXT_MUTED);
            seatGridPanel.add(frontL);
            seatGridPanel.add(new JLabel(""));
            seatGridPanel.add(new JLabel("AISLE", SwingConstants.CENTER));
            seatGridPanel.add(new JLabel(""));
            seatGridPanel.add(new JLabel(""));

            int seatNum = 1;
            for (int r = 0; r < rows; r++) {
                // Left 2 seats
                for (int col = 0; col < 2; col++) {
                    if (seatNum <= total) seatGridPanel.add(createSeatButton(seatNum++));
                    else seatGridPanel.add(new JLabel(""));
                }
                // Center Aisle
                JLabel aisle = new JLabel("|", SwingConstants.CENTER);
                aisle.setForeground(BORDER_COLOR);
                seatGridPanel.add(aisle);

                // Right 2 seats
                for (int col = 0; col < 2; col++) {
                    if (seatNum <= total) seatGridPanel.add(createSeatButton(seatNum++));
                    else seatGridPanel.add(new JLabel(""));
                }
            }

            selectedSeatText.setText(selectedSeat == -1 ? "Selected seat: None" : "Selected seat: #" + selectedSeat + " (Fare: Rs." + String.format("%.2f", selectedBus.getFare()) + ")");
        }
        seatGridPanel.revalidate();
        seatGridPanel.repaint();
    }

    private JButton createSeatButton(int seat) {
        JButton b = new JButton(String.valueOf(seat));
        b.setPreferredSize(new Dimension(52, 38));
        b.setFont(new Font("Segoe UI", Font.BOLD, 12));
        b.setForeground(Color.WHITE);
        b.setFocusPainted(false);
        b.setBorderPainted(false);
        b.setOpaque(true);

        if (selectedBus.isBooked(seat)) {
            b.setBackground(COLOR_BOOKED);
            b.setToolTipText("Seat #" + seat + " (Already Booked in DB)");
        } else if (seat == selectedSeat) {
            b.setBackground(COLOR_SELECTED);
            b.setToolTipText("Seat #" + seat + " (Your Selection)");
        } else {
            b.setBackground(COLOR_AVAILABLE);
            b.setToolTipText("Seat #" + seat + " (Available - Click to select)");
        }

        b.addActionListener(e -> {
            if (selectedBus.isBooked(seat)) {
                showError("Seat #" + seat + " is already booked for this bus. Please choose a green seat.");
                return;
            }
            selectedSeat = seat;
            drawSeats();
        });
        return b;
    }

    /* ===================== TABLE REFRESHERS ===================== */
    private void refreshBusTable() {
        updating = true;
        busModel.setRowCount(0);
        int sel = -1;
        for (int i = 0; i < currentList.size(); i++) {
            Bus b = currentList.get(i);
            busModel.addRow(new Object[]{
                    b.getBusId(), b.getName(), b.getSource(), b.getDestination(),
                    b.getDeparture(), String.format("%.2f", b.getFare()),
                    b.availableSeats() + " / " + b.getTotalSeats()
            });
            if (selectedBus != null && b.getBusId().equalsIgnoreCase(selectedBus.getBusId())) sel = i;
        }
        if (sel >= 0) {
            busTable.setRowSelectionInterval(sel, sel);
            selectedBus = currentList.get(sel);
        } else {
            busTable.clearSelection();
            selectedBus = null;
            selectedSeat = -1;
        }
        updating = false;
        drawSeats();
    }

    private void refreshTicketTable() {
        ticketModel.setRowCount(0);
        for (Ticket t : service.getAllTickets()) {
            ticketModel.addRow(new Object[]{
                    t.getTicketId(), t.getPassengerName(), t.getAge(),
                    t.getBusId(), t.getSeatNo(), String.format("%.2f", t.getFare()), t.getBookedAt()
            });
        }
    }

    private void refreshAdminTables() {
        adminBusModel.setRowCount(0);
        for (Bus b : service.getAllBuses()) {
            adminBusModel.addRow(new Object[]{
                    b.getBusId(), b.getName(), b.getSource(), b.getDestination(),
                    b.getDeparture(), b.getTotalSeats(), String.format("%.2f", b.getFare())
            });
        }

        adminTicketModel.setRowCount(0);
        for (Ticket t : service.getAllTickets()) {
            adminTicketModel.addRow(new Object[]{
                    t.getTicketId(), t.getPassengerName(), t.getAge(),
                    t.getBusId(), t.getSeatNo(), String.format("%.2f", t.getFare()), t.getBookedAt()
            });
        }
    }

    private void refreshStats() {
        Map<String, Object> stats = service.getStats();
        statBusesVal.setText(String.valueOf(stats.getOrDefault("total_buses", 0)));
        statTicketsVal.setText(String.valueOf(stats.getOrDefault("total_tickets", 0)));
        double rev = ((Number) stats.getOrDefault("total_revenue", 0.0)).doubleValue();
        statRevenueVal.setText("Rs. " + String.format("%.2f", rev));

        statDbModeBadge.setText(service.isSqliteActive() ? "SQLite 3 (Connected)" : "Local Engine");
        statDbModeBadge.setForeground(service.isSqliteActive() ? SUCCESS : WARNING);
    }

    /* ===================== UI HELPERS ===================== */
    private JButton createButton(String text, Color bg, Color fg) {
        JButton b = new JButton(text);
        b.setFont(new Font("Segoe UI", Font.BOLD, 12));
        b.setBackground(bg);
        b.setForeground(fg);
        b.setFocusPainted(false);
        b.setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(bg.darker(), 1, true),
                new EmptyBorder(6, 12, 6, 12)
        ));
        b.setOpaque(true);
        return b;
    }

    private void styleTable(JTable table) {
        table.setRowHeight(28);
        table.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        table.setGridColor(BORDER_COLOR);
        table.setSelectionBackground(new Color(219, 234, 254));
        table.setSelectionForeground(TEXT_DARK);

        JTableHeader th = table.getTableHeader();
        th.setFont(new Font("Segoe UI", Font.BOLD, 12));
        th.setBackground(new Color(241, 245, 249));
        th.setForeground(TEXT_DARK);
        th.setPreferredSize(new Dimension(0, 32));

        DefaultTableCellRenderer center = new DefaultTableCellRenderer();
        center.setHorizontalAlignment(SwingConstants.CENTER);
        for (int i = 0; i < table.getColumnCount(); i++) {
            if (i == 0 || i >= 4) table.getColumnModel().getColumn(i).setCellRenderer(center);
        }
    }

    private String selectedTicketId() {
        int r = ticketTable.getSelectedRow();
        if (r < 0) { showError("Please select a ticket from the table first."); return null; }
        return String.valueOf(ticketModel.getValueAt(r, 0));
    }

    private void showTicketDialog(Ticket t) {
        JTextArea area = new JTextArea(t.toReceiptText());
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        area.setEditable(false);
        area.setBackground(new Color(248, 250, 252));
        area.setBorder(new EmptyBorder(12, 12, 12, 12));

        JOptionPane.showMessageDialog(this, new JScrollPane(area),
                "SwiftTransit - Official Ticket Receipt - " + t.getTicketId(), JOptionPane.INFORMATION_MESSAGE);
    }

    private void showError(String msg) {
        JOptionPane.showMessageDialog(this, msg, "Validation / Error", JOptionPane.ERROR_MESSAGE);
        setStatus("Error: " + msg);
    }

    private void setStatus(String msg) {
        statusLabel.setText(" " + msg);
    }

    /* ===================== ENTRY POINT ===================== */
    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) { }
        SwingUtilities.invokeLater(() -> {
            try {
                new BusReservationGUI().setVisible(true);
            } catch (Throwable t) {
                t.printStackTrace();
                JOptionPane.showMessageDialog(null, "Error launching application:\n" + t.getMessage(), "Startup Error", JOptionPane.ERROR_MESSAGE);
            }
        });
    }
}
