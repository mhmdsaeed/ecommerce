-- Light or dark admin screens, chosen by each person and remembered on their account (merchant admin, driver view,
-- platform admin). DEVICE follows the phone or computer's setting.

ALTER TABLE staff_user ADD COLUMN color_scheme TEXT NOT NULL DEFAULT 'DEVICE';
ALTER TABLE staff_user ADD CONSTRAINT staff_user_color_scheme_ck CHECK (color_scheme IN ('DEVICE', 'LIGHT', 'DARK'));

ALTER TABLE platform_user ADD COLUMN color_scheme TEXT NOT NULL DEFAULT 'DEVICE';
ALTER TABLE platform_user ADD CONSTRAINT platform_user_color_scheme_ck
    CHECK (color_scheme IN ('DEVICE', 'LIGHT', 'DARK'));
