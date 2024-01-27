package org.firstinspires.ftc.teamcode.modules;

import androidx.annotation.NonNull;
import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.Servo;
import org.firstinspires.ftc.teamcode.hardware.ConditionalHardwareDevice;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;
import java.util.function.IntPredicate;

final class ArmAndWristMover {
    private final ConditionalHardwareDevice<DcMotor> armMotor;
    private final ConditionalHardwareDevice<Servo> wristServo;

    private final double armEpsilon;
    private final double wristEpsilon;

    private final IntPredicate wristDangerChecker;
    private final double safeWristPosition;
    private final BiPredicate<Integer, Double> pixelSafetyChecker;
    private final MotorPowerCalculator armPowerCalculator;

    private final HardwareInterface hardwareInterface;
    private final AtomicReference<RotationCommand> curCmd;

    public ArmAndWristMover(
            ConditionalHardwareDevice<DcMotor> armMotor,
            ConditionalHardwareDevice<Servo> wristServo,
            int armEpsilon,
            double wristEpsilon,
            IntPredicate wristDangerChecker,
            double safeWristPosition,
            BiPredicate<Integer, Double> pixelSafetyChecker,
            MotorPowerCalculator armPowerCalculator
    ) {
        this.armMotor = armMotor;
        this.wristServo = wristServo;
        this.armEpsilon = armEpsilon;
        this.wristEpsilon = wristEpsilon;
        this.wristDangerChecker = wristDangerChecker;
        this.safeWristPosition = safeWristPosition;
        this.pixelSafetyChecker = pixelSafetyChecker;
        this.armPowerCalculator = armPowerCalculator;
        this.hardwareInterface = new HardwareInterface();
        this.curCmd = new AtomicReference<>(
                new RotationCommand(hardwareInterface.getArmPosition(), hardwareInterface.getWristPosition(), WristRotationMode.DO_NOT_ROTATE)
        );
    }

    private enum WristRotationMode {ASAP, WITHOUT_DROPPING_PIXELS, COMPACT_WHILE_MOVING_ARM, DO_NOT_ROTATE}

    private final class HardwareInterface {
        private double prevArmPower;
        private double prevWristPosition;

        public synchronized int getArmPosition() {
            return armMotor.isAvailable() ? armMotor.requireDevice().getCurrentPosition() : 0;
        }

        public synchronized double getWristPosition() {
            return wristServo.isAvailable() ? wristServo.requireDevice().getPosition() : 0.0;
        }

        public synchronized boolean isArmWithinRangeOf(int expectedPosition) {
            return Math.abs(getArmPosition() - expectedPosition) < armEpsilon;
        }

        public synchronized boolean isWristWithinRangeOf(double expectedPosition) {
            return Math.abs(hardwareInterface.getWristPosition() - expectedPosition) < wristEpsilon;
        }

        public synchronized void setArmPower(double power) {
            if (power == prevArmPower) {
                return;
            }
            prevArmPower = power;
            armMotor.runIfAvailable(arm -> arm.setPower(power));
        }

        public synchronized void setWristPosition(double position) {
            if (position == prevWristPosition) {
                return;
            }
            prevWristPosition = position;
            wristServo.runIfAvailable(wrist -> wrist.setPosition(position));
        }
    }

    private final class RotationCommand {
        private final int armTargetPosition;
        private final double wristTargetPosition;
        private final WristRotationMode wristRotationMode;

        public RotationCommand(int armTargetPosition, double wristTargetPosition, WristRotationMode wristRotationMode) {
            this.armTargetPosition = armTargetPosition;
            this.wristTargetPosition = wristTargetPosition;
            this.wristRotationMode = wristRotationMode;
        }

        public int getArmTargetPosition() {
            return armTargetPosition;
        }

        public double getWristTargetPosition() {
            return wristTargetPosition;
        }

        public WristRotationMode getWristRotationMode() {
            return wristRotationMode;
        }

        public boolean isArmMovementCompleted() {
            return hardwareInterface.isArmWithinRangeOf(armTargetPosition);
        }

        public boolean isWristMovementCompleted() {
            return wristRotationMode == WristRotationMode.DO_NOT_ROTATE || hardwareInterface.isWristWithinRangeOf(wristTargetPosition);
        }

        public boolean hasMovementCompleted() {
            return isWristMovementCompleted() && isArmMovementCompleted();
        }

        @NonNull
        @Override
        public String toString() {
            return "{ Arm Target Position: " + armTargetPosition
                    + "(Done: " + isArmMovementCompleted() + "); "
                    + "Wrist Target Position: " + wristTargetPosition
                    + "(Done: " + isWristMovementCompleted() + ") }";
        }
    }

    private RotationCommand makeRotationCommand(int armTargetPosition, double wristTargetPosition) {
        if (wristTargetPosition < 0) { wristTargetPosition = 0; }
        else if (wristTargetPosition > 1) { wristTargetPosition = 1; }

        WristRotationMode wristRotationMode = WristRotationMode.ASAP;
        if (!pixelSafetyChecker.test(hardwareInterface.getArmPosition(), wristTargetPosition)) {
            wristRotationMode = WristRotationMode.WITHOUT_DROPPING_PIXELS;
        } else if (wristDangerChecker.test(armTargetPosition)) {
            wristRotationMode = WristRotationMode.COMPACT_WHILE_MOVING_ARM;
        }
        return new RotationCommand(armTargetPosition, wristTargetPosition, wristRotationMode);
    }

    public void moveArmAndWristAsync(int armTargetPosition, double wristTargetPosition) {
        curCmd.set(makeRotationCommand(armTargetPosition, wristTargetPosition));
    }

    public void setArmTargetPosition(int armTargetPosition) {
        curCmd.getAndUpdate(cmd -> makeRotationCommand(armTargetPosition, cmd.getWristTargetPosition()));
    }

    public void setWristTargetPosition(double wristTargetPosition) {
        curCmd.getAndUpdate(cmd -> makeRotationCommand(cmd.getArmTargetPosition(), wristTargetPosition));
    }

    public void moveArmAndWrist(int armTargetPosition, double wristTargetPosition) {
        final RotationCommand cmd = makeRotationCommand(armTargetPosition, wristTargetPosition);
        while (!cmd.hasMovementCompleted()) {
            cycleStateMachine();
        }
    }

    public int getArmTargetPosition() {
        return curCmd.get().getArmTargetPosition();
    }

    public double getWristTargetPosition() {
        return curCmd.get().getWristTargetPosition();
    }

    public void cycleStateMachine() {
        final RotationCommand cmd = curCmd.get();

        if (!cmd.isWristMovementCompleted()) {
            switch (cmd.getWristRotationMode()) {
                case DO_NOT_ROTATE:
                    break;
                case ASAP:
                    hardwareInterface.setWristPosition(cmd.getWristTargetPosition());
                    break;
                case COMPACT_WHILE_MOVING_ARM:
                    if (cmd.isArmMovementCompleted()) {
                        hardwareInterface.setWristPosition(cmd.getWristTargetPosition());
                    }
                    else {
                        hardwareInterface.setWristPosition(safeWristPosition);
                        if (!hardwareInterface.isWristWithinRangeOf(safeWristPosition)) {
                            return;
                        }
                    }
                    break;
                case WITHOUT_DROPPING_PIXELS:
                    if (pixelSafetyChecker.test(hardwareInterface.getArmPosition(), cmd.getWristTargetPosition())) {
                        hardwareInterface.setWristPosition(cmd.getWristTargetPosition());
                    }
                    break;
            }
        }

        if (!cmd.isArmMovementCompleted()) {
            hardwareInterface.setArmPower(armPowerCalculator.calculateMotorPower(hardwareInterface.getArmPosition(), cmd.getArmTargetPosition()));
        } else {
            hardwareInterface.setArmPower(0.0);
        }
    }

    public String getStatusString() {
        return curCmd.get().toString();
    }
}