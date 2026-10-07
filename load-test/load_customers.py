#!/usr/bin/env python3
"""Load fake customers into orch.customers for load testing.

Runs by hand, like db/*.sql; the service itself never writes to the table.
Needs only Python 3 and Docker: SQL is piped into `psql` inside the shared Postgres container.

Defaults: 1000 customers, 40% VOICE and 60% WHATSAPP (exact split, shuffled so the two channels
are interleaved), ids L0001..L1000 (the L prefix never collides with the default C1001..C1006),
obviously fake phone numbers. Idempotent: re-running inserts nothing new (ON CONFLICT DO NOTHING).

Examples:
  python3 load-test/load_customers.py                    # load 1000 (400 VOICE / 600 WHATSAPP)
  python3 load-test/load_customers.py --dry-run | head   # print the SQL, touch nothing
  python3 load-test/load_customers.py --count 5000 --voice-pct 40
  python3 load-test/load_customers.py --clean            # delete every customer with the load prefix
"""
import argparse
import random
import subprocess
import sys


def build_rows(count, voice_pct, prefix, seed):
    voice = round(count * voice_pct / 100)
    channels = ["VOICE"] * voice + ["WHATSAPP"] * (count - voice)
    random.Random(seed).shuffle(channels)
    width = max(4, len(str(count)))
    rows = []
    for i, channel in enumerate(channels, start=1):
        cid = f"{prefix}{i:0{width}d}"
        phone = f"+919000{i:06d}"  # 13 chars, fits VARCHAR(20), obviously fake, differs from the default customers
        wa_id = f"919000{i:06d}"  # same digits without the +; obviously fake so a load test never messages a real number
        rows.append((cid, f"Load Customer {i}", phone, wa_id, channel))
    return rows


def insert_sql(rows, batch):
    out = ["BEGIN;"]
    for start in range(0, len(rows), batch):
        values = ",\n".join(f"  ('{c}', '{n}', '{p}', '{w}', '{ch}')" for c, n, p, w, ch in rows[start:start + batch])
        out.append("INSERT INTO orch.customers (customer_id, name, phone, wa_id, initial_channel) VALUES\n"
                   f"{values}\nON CONFLICT (customer_id) DO NOTHING;")
    out.append("COMMIT;")
    return "\n".join(out) + "\n"


def run_psql(args, sql):
    cmd = ["docker", "exec", "-i", args.container, "psql", "-v", "ON_ERROR_STOP=1",
           "-U", args.user, "-d", args.db]
    return subprocess.run(cmd, input=sql, text=True, capture_output=True)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--count", type=int, default=1000, help="number of customers (default 1000)")
    ap.add_argument("--voice-pct", type=float, default=40, help="percent VOICE, rest WHATSAPP (default 40)")
    ap.add_argument("--prefix", default="L", help="customer id prefix (default L)")
    ap.add_argument("--seed", type=int, default=42, help="shuffle seed, same seed = same layout (default 42)")
    ap.add_argument("--batch", type=int, default=500, help="rows per INSERT statement (default 500)")
    ap.add_argument("--container", default="cern-v2-postgres", help="shared Postgres container name")
    ap.add_argument("--user", default="cern")
    ap.add_argument("--db", default="cern_v2")
    ap.add_argument("--dry-run", action="store_true", help="print the SQL and exit")
    ap.add_argument("--clean", action="store_true", help="delete all customers whose id starts with --prefix")
    args = ap.parse_args()

    if not 0 <= args.voice_pct <= 100 or args.count < 1 or not args.prefix.isalnum():
        sys.exit("invalid arguments: need count >= 1, 0 <= voice-pct <= 100, alphanumeric prefix")

    if args.clean:
        sql = f"DELETE FROM orch.customers WHERE customer_id LIKE '{args.prefix}%';\n"
    else:
        sql = insert_sql(build_rows(args.count, args.voice_pct, args.prefix, args.seed), args.batch)

    if args.dry_run:
        print(sql)
        return

    result = run_psql(args, sql)
    if result.returncode != 0:
        sys.exit(f"psql failed:\n{result.stderr or result.stdout}")
    print(result.stdout.strip())

    summary = run_psql(args, "SELECT initial_channel, count(*) FROM orch.customers GROUP BY 1 ORDER BY 1;\n")
    print("customers in table now (all ids, including the default C1001..C1006):")
    print(summary.stdout.strip())


if __name__ == "__main__":
    main()
