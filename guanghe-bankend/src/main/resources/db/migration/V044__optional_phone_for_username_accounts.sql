-- Username-only accounts use a real NULL phone. Existing phone uniqueness remains enforced.
ALTER TABLE business.account ALTER COLUMN phone_e164 DROP NOT NULL;
